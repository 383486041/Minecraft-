param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

function New-Canvas([int]$Width, [int]$Height) {
    $bitmap = [System.Drawing.Bitmap]::new(
        $Width,
        $Height,
        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb
    )
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.Clear([System.Drawing.Color]::Transparent)
    } finally {
        $graphics.Dispose()
    }
    return $bitmap
}

function Get-Color([string]$Hex) {
    return [System.Drawing.ColorTranslator]::FromHtml($Hex)
}

function Fill-Rect(
    [System.Drawing.Bitmap]$Bitmap,
    [int]$X,
    [int]$Y,
    [int]$Width,
    [int]$Height,
    [System.Drawing.Color]$Color
) {
    for ($py = $Y; $py -lt $Y + $Height; $py++) {
        for ($px = $X; $px -lt $X + $Width; $px++) {
            if ($px -ge 0 -and $px -lt $Bitmap.Width -and $py -ge 0 -and $py -lt $Bitmap.Height) {
                $Bitmap.SetPixel($px, $py, $Color)
            }
        }
    }
}

function Set-Pixel(
    [System.Drawing.Bitmap]$Bitmap,
    [int]$X,
    [int]$Y,
    [System.Drawing.Color]$Color
) {
    if ($X -ge 0 -and $X -lt $Bitmap.Width -and $Y -ge 0 -and $Y -lt $Bitmap.Height) {
        $Bitmap.SetPixel($X, $Y, $Color)
    }
}

function Save-Png([System.Drawing.Bitmap]$Bitmap, [string]$Path) {
    if ($null -eq $Bitmap) {
        throw "PNG 位图为空：$Path"
    }
    $directory = Split-Path -Parent $Path
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    $Bitmap.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
    $Bitmap.Dispose()
}

$shadow = Get-Color '#1A2228'
$deep = Get-Color '#303A42'
$navy = Get-Color '#45535C'
$blue = Get-Color '#687A84'
$steel = Get-Color '#87969E'
$teal = Get-Color '#35BFD1'
$cyan = Get-Color '#73E2F0'
$mint = Get-Color '#B9F8FC'
$glow = Get-Color '#F0FFFF'
$purple = Get-Color '#AAB7BD'
$magenta = Get-Color '#CBD5D9'
$violet = Get-Color '#56636B'

$textureRoot = Join-Path $ProjectRoot 'src\main\resources\assets\flight_armor\textures'
$armorRoot = Join-Path $textureRoot 'models\armor'
$itemRoot = Join-Path $textureRoot 'item'
$geoRoot = Join-Path $textureRoot 'armor'

# layer_1：头盔、胸甲、手臂与战靴。
$layer1 = [System.Drawing.Bitmap]::new(64, 32, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)

# 头盔 UV。
Fill-Rect $layer1 0 0 32 16 $deep
Fill-Rect $layer1 8 0 8 8 $blue
Fill-Rect $layer1 9 1 6 1 $cyan
Fill-Rect $layer1 16 0 8 8 $shadow
Fill-Rect $layer1 0 8 8 8 $navy
Fill-Rect $layer1 2 9 2 6 $purple
Fill-Rect $layer1 8 8 8 8 $navy
Fill-Rect $layer1 9 9 6 3 $cyan
Fill-Rect $layer1 10 9 4 1 $glow
Fill-Rect $layer1 10 13 4 2 $shadow
Set-Pixel $layer1 11 13 $purple
Set-Pixel $layer1 12 13 $magenta
Fill-Rect $layer1 16 8 8 8 $navy
Fill-Rect $layer1 20 9 2 6 $cyan
Fill-Rect $layer1 24 8 8 8 $shadow
Fill-Rect $layer1 25 12 6 2 $purple

# 胸甲 UV。
Fill-Rect $layer1 16 16 24 16 $deep
Fill-Rect $layer1 20 16 8 4 $blue
Fill-Rect $layer1 16 20 4 12 $navy
Fill-Rect $layer1 20 20 8 12 $navy
Fill-Rect $layer1 28 20 4 12 $navy
Fill-Rect $layer1 32 20 8 12 $shadow
Set-Pixel $layer1 21 21 $cyan
Set-Pixel $layer1 22 22 $cyan
Set-Pixel $layer1 26 21 $purple
Set-Pixel $layer1 25 22 $purple
Set-Pixel $layer1 23 23 $cyan
Set-Pixel $layer1 24 23 $glow
Set-Pixel $layer1 22 24 $cyan
Set-Pixel $layer1 23 24 $glow
Set-Pixel $layer1 24 24 $magenta
Set-Pixel $layer1 25 24 $purple
Set-Pixel $layer1 23 25 $magenta
Set-Pixel $layer1 24 25 $purple
Fill-Rect $layer1 21 29 6 2 $blue
Set-Pixel $layer1 20 30 $cyan
Set-Pixel $layer1 27 30 $purple

# 双臂共用 UV。
Fill-Rect $layer1 40 16 16 16 $deep
Fill-Rect $layer1 44 16 4 4 $purple
Fill-Rect $layer1 40 20 4 12 $navy
Fill-Rect $layer1 44 20 4 12 $blue
Fill-Rect $layer1 48 20 4 12 $navy
Fill-Rect $layer1 52 20 4 12 $shadow
Fill-Rect $layer1 45 21 2 7 $cyan
Set-Pixel $layer1 45 21 $glow
Fill-Rect $layer1 44 29 4 2 $purple

# 战靴共用腿部 UV。
Fill-Rect $layer1 0 16 16 16 $deep
Fill-Rect $layer1 4 16 4 4 $blue
Fill-Rect $layer1 0 20 4 12 $navy
Fill-Rect $layer1 4 20 4 12 $navy
Fill-Rect $layer1 8 20 4 12 $navy
Fill-Rect $layer1 12 20 4 12 $shadow
Fill-Rect $layer1 5 26 2 4 $cyan
Set-Pixel $layer1 5 26 $glow
Fill-Rect $layer1 4 30 4 2 $purple
Fill-Rect $layer1 0 29 4 3 $blue

# 二代外观收束：环形面罩、胸前悬浮核心、肩部矢量推进器与靴底喷口。
# 这些像素均在原版盔甲 UV 区内，不依赖外部模型。
Fill-Rect $layer1 8 0 8 1 $violet
Fill-Rect $layer1 8 1 1 6 $steel
Fill-Rect $layer1 15 1 1 6 $steel
Fill-Rect $layer1 9 2 6 1 $mint
Fill-Rect $layer1 9 9 6 1 $mint
Fill-Rect $layer1 10 10 4 2 $teal
Set-Pixel $layer1 11 11 $glow
Set-Pixel $layer1 12 11 $glow
Fill-Rect $layer1 0 10 1 5 $steel
Fill-Rect $layer1 23 10 1 5 $steel
Set-Pixel $layer1 2 10 $mint
Set-Pixel $layer1 21 10 $mint

Fill-Rect $layer1 20 16 8 1 $violet
Fill-Rect $layer1 20 20 8 1 $steel
Fill-Rect $layer1 20 21 1 8 $teal
Fill-Rect $layer1 27 21 1 8 $violet
Fill-Rect $layer1 22 21 4 1 $mint
Fill-Rect $layer1 22 22 4 4 $blue
Fill-Rect $layer1 23 22 2 1 $teal
Fill-Rect $layer1 22 23 1 2 $teal
Fill-Rect $layer1 25 23 1 2 $purple
Fill-Rect $layer1 23 24 2 2 $mint
Set-Pixel $layer1 23 25 $glow
Set-Pixel $layer1 24 25 $magenta
Fill-Rect $layer1 21 27 6 1 $steel
Fill-Rect $layer1 22 29 4 1 $teal
Fill-Rect $layer1 16 21 3 2 $violet
Fill-Rect $layer1 29 21 3 2 $violet
Set-Pixel $layer1 17 22 $mint
Set-Pixel $layer1 30 22 $mint

Fill-Rect $layer1 44 16 4 1 $violet
Fill-Rect $layer1 44 20 4 1 $steel
Fill-Rect $layer1 45 21 1 7 $mint
Fill-Rect $layer1 46 21 1 7 $teal
Fill-Rect $layer1 47 22 1 5 $purple
Fill-Rect $layer1 44 28 4 1 $steel
Set-Pixel $layer1 45 29 $glow
Set-Pixel $layer1 46 29 $magenta

Fill-Rect $layer1 4 20 4 1 $steel
Fill-Rect $layer1 5 22 1 5 $teal
Fill-Rect $layer1 6 22 1 5 $mint
Fill-Rect $layer1 4 28 4 1 $violet
Fill-Rect $layer1 4 30 4 1 $steel
Fill-Rect $layer1 4 31 4 1 $deep
Set-Pixel $layer1 5 30 $mint
Set-Pixel $layer1 6 30 $glow

Save-Png -Bitmap $layer1 -Path (Join-Path $armorRoot 'flight_layer_1.png')

# layer_2：护腿与腰甲。未使用区域保持透明。
$layer2 = [System.Drawing.Bitmap]::new(64, 32, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
Fill-Rect $layer2 0 16 16 16 $deep
Fill-Rect $layer2 4 16 4 4 $blue
Fill-Rect $layer2 0 20 4 12 $navy
Fill-Rect $layer2 4 20 4 12 $blue
Fill-Rect $layer2 8 20 4 12 $navy
Fill-Rect $layer2 12 20 4 12 $shadow
Fill-Rect $layer2 5 21 2 8 $cyan
Set-Pixel $layer2 5 21 $glow
Fill-Rect $layer2 4 29 4 3 $purple

Fill-Rect $layer2 16 16 24 4 $deep
Fill-Rect $layer2 16 20 24 12 $([System.Drawing.Color]::Transparent)
Fill-Rect $layer2 16 26 24 6 $deep
Fill-Rect $layer2 20 26 8 6 $navy
Fill-Rect $layer2 21 27 6 2 $blue
Set-Pixel $layer2 23 28 $cyan
Set-Pixel $layer2 24 28 $purple
Fill-Rect $layer2 16 29 4 3 $purple
Fill-Rect $layer2 28 29 4 3 $cyan
Fill-Rect $layer2 32 29 8 3 $shadow

# 腰部稳定环与双腿能量导轨。
Fill-Rect $layer2 4 16 4 1 $violet
Fill-Rect $layer2 4 20 4 1 $steel
Fill-Rect $layer2 5 21 1 7 $mint
Fill-Rect $layer2 6 21 1 7 $teal
Fill-Rect $layer2 4 28 4 1 $violet
Set-Pixel $layer2 5 29 $glow
Set-Pixel $layer2 6 29 $magenta
Fill-Rect $layer2 16 26 24 1 $violet
Fill-Rect $layer2 20 27 8 1 $steel
Fill-Rect $layer2 21 28 6 1 $teal
Fill-Rect $layer2 22 29 4 1 $mint
Set-Pixel $layer2 23 29 $glow
Set-Pixel $layer2 24 29 $magenta

Save-Png -Bitmap $layer2 -Path (Join-Path $armorRoot 'flight_layer_2.png')

# 头盔图标。
$helmet = [System.Drawing.Bitmap]::new(16, 16, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
Fill-Rect $helmet 4 2 8 2 $purple
Fill-Rect $helmet 3 4 10 7 $deep
Fill-Rect $helmet 4 3 8 1 $blue
Fill-Rect $helmet 4 5 8 3 $cyan
Fill-Rect $helmet 5 5 6 1 $glow
Fill-Rect $helmet 4 8 2 4 $navy
Fill-Rect $helmet 10 8 2 4 $navy
Fill-Rect $helmet 6 9 4 2 $shadow
Set-Pixel $helmet 7 11 $magenta
Set-Pixel $helmet 8 11 $purple
Fill-Rect $helmet 5 1 6 1 $violet
Fill-Rect $helmet 4 4 8 1 $steel
Fill-Rect $helmet 5 5 6 1 $mint
Set-Pixel $helmet 7 6 $glow
Set-Pixel $helmet 8 6 $glow
Save-Png -Bitmap $helmet -Path (Join-Path $itemRoot 'flight_helmet.png')

# 胸甲图标。
$chestplate = [System.Drawing.Bitmap]::new(16, 16, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
Fill-Rect $chestplate 2 3 4 3 $purple
Fill-Rect $chestplate 10 3 4 3 $cyan
Fill-Rect $chestplate 4 3 8 10 $deep
Fill-Rect $chestplate 3 5 2 6 $navy
Fill-Rect $chestplate 11 5 2 6 $navy
Fill-Rect $chestplate 5 4 6 8 $blue
Set-Pixel $chestplate 7 6 $cyan
Set-Pixel $chestplate 8 6 $glow
Set-Pixel $chestplate 6 7 $cyan
Set-Pixel $chestplate 7 7 $glow
Set-Pixel $chestplate 8 7 $magenta
Set-Pixel $chestplate 9 7 $purple
Fill-Rect $chestplate 6 11 4 3 $deep
Fill-Rect $chestplate 5 4 6 1 $steel
Fill-Rect $chestplate 6 5 4 1 $mint
Fill-Rect $chestplate 6 8 4 2 $teal
Set-Pixel $chestplate 7 8 $glow
Set-Pixel $chestplate 8 8 $magenta
Save-Png -Bitmap $chestplate -Path (Join-Path $itemRoot 'flight_chestplate.png')

# 护腿图标。
$leggings = [System.Drawing.Bitmap]::new(16, 16, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
Fill-Rect $leggings 3 2 10 3 $deep
Fill-Rect $leggings 4 3 8 2 $blue
Fill-Rect $leggings 3 5 4 8 $navy
Fill-Rect $leggings 9 5 4 8 $navy
Fill-Rect $leggings 4 6 2 6 $cyan
Fill-Rect $leggings 10 6 2 6 $purple
Fill-Rect $leggings 3 12 4 2 $deep
Fill-Rect $leggings 9 12 4 2 $deep
Set-Pixel $leggings 7 4 $glow
Set-Pixel $leggings 8 4 $magenta
Fill-Rect $leggings 4 5 2 1 $steel
Fill-Rect $leggings 10 5 2 1 $steel
Set-Pixel $leggings 5 8 $mint
Set-Pixel $leggings 10 8 $mint
Save-Png -Bitmap $leggings -Path (Join-Path $itemRoot 'flight_leggings.png')

# 战靴图标。
$boots = [System.Drawing.Bitmap]::new(16, 16, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
Fill-Rect $boots 2 4 5 7 $navy
Fill-Rect $boots 9 4 5 7 $navy
Fill-Rect $boots 3 5 3 5 $blue
Fill-Rect $boots 10 5 3 5 $blue
Fill-Rect $boots 3 8 2 3 $cyan
Fill-Rect $boots 11 8 2 3 $purple
Fill-Rect $boots 1 10 7 4 $deep
Fill-Rect $boots 8 10 7 4 $deep
Fill-Rect $boots 2 12 6 2 $purple
Fill-Rect $boots 8 12 6 2 $cyan
Set-Pixel $boots 4 8 $glow
Set-Pixel $boots 11 8 $glow
Fill-Rect $boots 2 11 6 1 $steel
Fill-Rect $boots 8 11 6 1 $steel
Set-Pixel $boots 4 11 $mint
Set-Pixel $boots 11 11 $mint
Save-Png -Bitmap $boots -Path (Join-Path $itemRoot 'flight_boots.png')

# 飞行激活层：专供 3D 核心、导轨和喷口使用，全亮度渲染时不受环境光影响。
$glowTexture = New-Canvas 64 32
Fill-Rect $glowTexture 0 0 32 32 $teal
Fill-Rect $glowTexture 32 0 32 32 $purple
Fill-Rect $glowTexture 8 8 16 16 $mint
Fill-Rect $glowTexture 40 8 16 16 $magenta
Set-Pixel $glowTexture 15 15 $glow
Set-Pixel $glowTexture 48 15 $glow
Save-Png -Bitmap $glowTexture -Path (Join-Path $armorRoot 'flight_glow.png')

# GeckoLib 骨骼盔甲使用 256x256 材质调色板。
# 四个 64 像素宽的材质区依次为：枪灰内骨架、钛白外甲、冰蓝能量、深色阴影。
$geoArmor = New-Canvas 256 256
Fill-Rect $geoArmor 0 0 64 256 $deep
Fill-Rect $geoArmor 64 0 64 256 $purple
Fill-Rect $geoArmor 128 0 64 256 $cyan
Fill-Rect $geoArmor 192 0 64 256 $shadow

# 为大面积钛白甲片加入极轻的冷灰分层，靠光照而不是花纹表现结构。
Fill-Rect $geoArmor 64 0 64 8 $magenta
Fill-Rect $geoArmor 64 48 64 8 $steel
Fill-Rect $geoArmor 0 48 64 8 $navy
Fill-Rect $geoArmor 128 0 64 8 $glow
Save-Png -Bitmap $geoArmor -Path (Join-Path $geoRoot 'flight_armor.png')

$geoGlow = New-Canvas 256 256
Fill-Rect $geoGlow 128 0 64 256 $cyan
Fill-Rect $geoGlow 128 0 64 8 $glow
Save-Png -Bitmap $geoGlow -Path (Join-Path $geoRoot 'flight_armor_glowmask.png')

Write-Output "已生成凌空战甲像素材质：$textureRoot"
