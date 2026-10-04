#!/bin/bash
set -e

declare -A sizes=( ["mdpi"]="48x48" ["hdpi"]="72x72" ["xhdpi"]="96x96" ["xxhdpi"]="144x144" ["xxxhdpi"]="192x192" )

for density in "${!sizes[@]}"; do
    size=${sizes[$density]}
    
    mkdir -p "app/src/main/res/mipmap-$density"
    convert light-logo.png -resize $size "app/src/main/res/mipmap-$density/ic_launcher.png"
    convert light-logo.png -resize $size "app/src/main/res/mipmap-$density/ic_launcher_round.png"
    
    mkdir -p "app/src/main/res/mipmap-night-$density"
    convert dark-logo.png -resize $size "app/src/main/res/mipmap-night-$density/ic_launcher.png"
    convert dark-logo.png -resize $size "app/src/main/res/mipmap-night-$density/ic_launcher_round.png"
done

mkdir -p app/src/main/res/drawable
mkdir -p app/src/main/res/drawable-night

convert light-logo.png -resize 432x432 "app/src/main/res/drawable/ic_launcher_foreground.png"
convert dark-logo.png -resize 432x432 "app/src/main/res/drawable-night/ic_launcher_foreground.png"

echo "Done"
