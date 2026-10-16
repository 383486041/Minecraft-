[CmdletBinding()]
param(
    [switch] $Full,
    [switch] $Install,
    [string] $JavaHome = 'D:\AAAA\Android Studio\jbr',
    [string] $ModsDir = 'D:\MinecraftTest\FlightArmor-1.21.1\.minecraft\versions\1.21.1-Fabric 0.19.3\mods',
    [string] $GradleUserHome = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.IO.Compression.FileSystem

$script:ModId = 'flight_armor'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$gradleWrapper = Join-Path $projectRoot 'gradlew.bat'

function Get-ProjectVersion {
    param([Parameter(Mandatory = $true)] [string] $PropertiesFile)

    $versionLine = Get-Content -LiteralPath $PropertiesFile -Encoding UTF8 |
        Where-Object { $_ -match '^\s*mod_version\s*=' } |
        Select-Object -First 1
    if (-not $versionLine) {
        throw 'gradle.properties does not define mod_version.'
    }

    return ($versionLine -replace '^\s*mod_version\s*=\s*', '').Trim()
}

function Test-FileUnlocked {
    param([Parameter(Mandatory = $true)] [string] $Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return $true
    }

    $stream = $null
    try {
        $stream = [System.IO.File]::Open(
            $Path,
            [System.IO.FileMode]::Open,
            [System.IO.FileAccess]::Read,
            [System.IO.FileShare]::None
        )
        return $true
    }
    catch [System.IO.IOException] {
        return $false
    }
    finally {
        if ($null -ne $stream) {
            $stream.Dispose()
        }
    }
}

function Test-IsFlightArmorJar {
    param([Parameter(Mandatory = $true)] [System.IO.FileInfo] $Jar)

    # The standard name remains detectable even when Minecraft has locked the JAR.
    if ($Jar.Name -match '^flight_armor(?:-|\.jar$)') {
        return $true
    }

    $archive = $null
    $reader = $null
    try {
        $archive = [System.IO.Compression.ZipFile]::OpenRead($Jar.FullName)
        $entry = $archive.GetEntry('fabric.mod.json')
        if ($null -eq $entry) {
            return $false
        }

        $reader = [System.IO.StreamReader]::new($entry.Open(), [System.Text.Encoding]::UTF8)
        $metadata = $reader.ReadToEnd() | ConvertFrom-Json
        return [string]$metadata.id -eq $script:ModId
    }
    catch {
        return $false
    }
    finally {
        if ($null -ne $reader) {
            $reader.Dispose()
        }
        if ($null -ne $archive) {
            $archive.Dispose()
        }
    }
}

function Get-InstalledFlightArmorJars {
    param([Parameter(Mandatory = $true)] [string] $Directory)

    if (-not (Test-Path -LiteralPath $Directory -PathType Container)) {
        return @()
    }

    return @(
        Get-ChildItem -LiteralPath $Directory -File -Filter '*.jar' |
            Where-Object { Test-IsFlightArmorJar -Jar $_ }
    )
}

function Get-RunningMinecraftProcesses {
    $processMatches = @()
    try {
        $processes = Get-CimInstance -ClassName Win32_Process -ErrorAction Stop |
            Where-Object { $_.Name -match '^(java|javaw|minecraft|minecraftlauncher)(\.exe)?$' }

        foreach ($process in $processes) {
            $name = [string]$process.Name
            $commandLine = [string]$process.CommandLine
            $isMinecraftJava = $name -match '^javaw?(\.exe)?$' -and
                $commandLine -match '(?i)(\.minecraft|net\.minecraft|fabric-loader|net\.fabricmc\.loader|minecraft\.client\.main)'
            $isMinecraftExecutable = $name -match '^minecraft(launcher)?(\.exe)?$'
            if ($isMinecraftJava -or $isMinecraftExecutable) {
                $processMatches += [PSCustomObject]@{
                    Id = [int]$process.ProcessId
                    Name = $name
                }
            }
        }
    }
    catch {
        # A Gradle daemon uses java.exe, so the conservative fallback only blocks javaw.
        $fallback = Get-Process -Name 'javaw', 'minecraft', 'minecraftlauncher' -ErrorAction SilentlyContinue
        foreach ($process in $fallback) {
            $processMatches += [PSCustomObject]@{
                Id = $process.Id
                Name = $process.ProcessName
            }
        }
    }

    return @($processMatches | Sort-Object Id -Unique)
}

function Assert-InstallIsSafe {
    param([Parameter(Mandatory = $true)] [string] $Directory)

    $runningClients = @(Get-RunningMinecraftProcesses)
    if ($runningClients.Count -gt 0) {
        $description = ($runningClients | ForEach-Object { '{0}(PID {1})' -f $_.Name, $_.Id }) -join ', '
        throw "Minecraft is still running: $description. Close the game before using -Install."
    }

    foreach ($jar in @(Get-InstalledFlightArmorJars -Directory $Directory)) {
        if (-not (Test-FileUnlocked -Path $jar.FullName)) {
            throw "The target mod JAR is locked: $($jar.FullName). Close the game before installing."
        }
    }
}

function Install-FlightArmorJar {
    param(
        [Parameter(Mandatory = $true)] [string] $Artifact,
        [Parameter(Mandatory = $true)] [string] $Directory
    )

    $modsFullPath = [System.IO.Path]::GetFullPath($Directory).TrimEnd('\')
    $modsParent = Split-Path -Parent $modsFullPath
    $backupDirectory = Join-Path $modsParent '_flight_armor_backup'
    $artifactFile = Get-Item -LiteralPath $Artifact
    $targetPath = Join-Path $modsFullPath $artifactFile.Name
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmssfff'
    $stagedPath = Join-Path $backupDirectory ('.installing-{0}-{1}' -f ([Guid]::NewGuid().ToString('N')), $artifactFile.Name)
    $backups = @()
    $installedNewJar = $false

    New-Item -ItemType Directory -Path $modsFullPath -Force | Out-Null
    New-Item -ItemType Directory -Path $backupDirectory -Force | Out-Null

    try {
        # Stage and hash the new artifact before any active JAR is moved.
        Copy-Item -LiteralPath $artifactFile.FullName -Destination $stagedPath
        $sourceHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $artifactFile.FullName).Hash
        $stagedHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $stagedPath).Hash
        if ($sourceHash -ne $stagedHash) {
            throw 'The staged JAR failed SHA256 verification.'
        }

        # Minecraft might have started while Gradle was building, so check again here.
        Assert-InstallIsSafe -Directory $modsFullPath
        $activeJars = @(Get-InstalledFlightArmorJars -Directory $modsFullPath)
        $index = 0
        foreach ($jar in $activeJars) {
            $index++
            $backupName = '{0}.{1}.{2}.bak' -f $jar.Name, $stamp, $index
            $backupPath = Join-Path $backupDirectory $backupName
            Move-Item -LiteralPath $jar.FullName -Destination $backupPath
            $backups += [PSCustomObject]@{
                Original = $jar.FullName
                Backup = $backupPath
            }
        }

        Move-Item -LiteralPath $stagedPath -Destination $targetPath
        $installedNewJar = $true

        $remaining = @(Get-InstalledFlightArmorJars -Directory $modsFullPath)
        if ($remaining.Count -ne 1 -or $remaining[0].FullName -ne $targetPath) {
            throw 'Expected exactly one active flight_armor JAR after installation.'
        }

        $installedHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $targetPath).Hash
        Write-Host "Installed: $targetPath" -ForegroundColor Green
        Write-Host "SHA256: $installedHash"
        if ($backups.Count -gt 0) {
            Write-Host "Previous versions backed up to: $backupDirectory"
        }
    }
    catch {
        $installError = $_
        if ($installedNewJar -and (Test-Path -LiteralPath $targetPath)) {
            Remove-Item -LiteralPath $targetPath -Force
        }
        foreach ($record in $backups) {
            if ((Test-Path -LiteralPath $record.Backup) -and -not (Test-Path -LiteralPath $record.Original)) {
                Move-Item -LiteralPath $record.Backup -Destination $record.Original
            }
        }
        if (Test-Path -LiteralPath $stagedPath) {
            Remove-Item -LiteralPath $stagedPath -Force
        }
        throw $installError
    }
}

if (-not (Test-Path -LiteralPath $gradleWrapper -PathType Leaf)) {
    throw "Gradle Wrapper not found: $gradleWrapper"
}

$javaExecutable = Join-Path $JavaHome 'bin\java.exe'
if (-not (Test-Path -LiteralPath $javaExecutable -PathType Leaf)) {
    throw "Java 21 was not found at $javaExecutable. Override it with -JavaHome."
}

$env:JAVA_HOME = [System.IO.Path]::GetFullPath($JavaHome)
$env:Path = "$(Join-Path $env:JAVA_HOME 'bin');$env:Path"

# Keep Gradle's writable locks and caches inside the project. A warmed test cache is preferred
# for fast iteration; fresh checkouts fall back to a generic local cache.
if ([string]::IsNullOrWhiteSpace($GradleUserHome)) {
    $warmCache = Join-Path $projectRoot '.gradle-user-home-test'
    $GradleUserHome = if (Test-Path -LiteralPath $warmCache -PathType Container) {
        $warmCache
    }
    else {
        Join-Path $projectRoot '.gradle-user-home'
    }
}
$env:GRADLE_USER_HOME = [System.IO.Path]::GetFullPath($GradleUserHome)
New-Item -ItemType Directory -Path $env:GRADLE_USER_HOME -Force | Out-Null

# Reuse an already installed wrapper distribution directly when available. This avoids the
# wrapper .zip.lck contention seen during rapid parallel builds, while retaining wrapper fallback.
$cachedGradleLauncher = $null
$wrapperProperties = Join-Path $projectRoot 'gradle\wrapper\gradle-wrapper.properties'
$distributionLine = Get-Content -LiteralPath $wrapperProperties -Encoding UTF8 |
    Where-Object { $_ -match '^distributionUrl=' } | Select-Object -First 1
if ($distributionLine -match 'gradle-([0-9][0-9.]*)-bin\.zip') {
    $gradleVersion = $Matches[1]
    $globalGradleHome = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.gradle'
    $distributionRoot = Join-Path $globalGradleHome "wrapper\dists\gradle-$gradleVersion-bin"
    if (Test-Path -LiteralPath $distributionRoot -PathType Container) {
        $cachedGradleLauncher = Get-ChildItem -LiteralPath $distributionRoot -Recurse -File `
            -Filter "gradle-launcher-$gradleVersion.jar" -ErrorAction SilentlyContinue |
            Select-Object -First 1 -ExpandProperty FullName
    }
}

$version = Get-ProjectVersion -PropertiesFile (Join-Path $projectRoot 'gradle.properties')
$artifact = Join-Path $projectRoot "build\libs\flight_armor-$version.jar"
$task = 'remapJar'
if ($Full) {
    $task = 'build'
}

if ($Install) {
    Assert-InstallIsSafe -Directory $ModsDir
}

$gradleArguments = @(
    $task,
    # Codex may finish several isolated verification lanes close together. A shared Windows
    # daemon registry can then accept a stale socket and fail with "Unexpected authentication
    # token". A single-use daemon still reuses the configuration/build caches and is deterministic.
    '--no-daemon',
    '--no-parallel',
    '--max-workers=1',
    '--build-cache',
    '--console=plain'
)

$modeName = 'fast incremental remapJar'
if ($Full) {
    $modeName = 'full build'
}
Write-Host "Build mode: $modeName" -ForegroundColor Cyan
Write-Host "Java: $env:JAVA_HOME"
Write-Host "Gradle user home: $env:GRADLE_USER_HOME"
Write-Host "Gradle runner: $(if ($cachedGradleLauncher) { $cachedGradleLauncher } else { $gradleWrapper })"

Push-Location $projectRoot
try {
    # javac writes note/deprecation diagnostics to stderr. Under $ErrorActionPreference='Stop',
    # Windows PowerShell 5.1 turns any native stderr line into a terminating
    # NativeCommandError, which aborts a build that had in fact succeeded. Demote it for the
    # duration of the Gradle call and judge success by the exit code instead.
    $hostErrorPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    # 必须用 Write-Output：Write-Host 直接写主机、不进管道，外层的 Tee-Object 会因此
    # 抓不到任何 Gradle 输出，日志文件恒为空（本轮实际踩到，排查时差点没看到编译错误）。
    try {
        if ($cachedGradleLauncher) {
            & $javaExecutable -classpath $cachedGradleLauncher org.gradle.launcher.GradleMain @gradleArguments 2>&1 |
                ForEach-Object { Write-Output ([string]$_) }
        }
        else {
            & $gradleWrapper @gradleArguments 2>&1 |
                ForEach-Object { Write-Output ([string]$_) }
        }
    }
    finally {
        $ErrorActionPreference = $hostErrorPreference
    }
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle failed with exit code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}

if (-not (Test-Path -LiteralPath $artifact -PathType Leaf)) {
    throw "Gradle completed but the current artifact was not found: $artifact"
}

$artifactHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $artifact).Hash
Write-Host "Built: $artifact" -ForegroundColor Green
Write-Host "SHA256: $artifactHash"

if ($Install) {
    Install-FlightArmorJar -Artifact $artifact -Directory $ModsDir
}
