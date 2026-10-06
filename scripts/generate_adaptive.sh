#!/bin/bash
set -e

declare -A sizes=( ["mdpi"]="108x108" ["hdpi"]="162x162" ["xhdpi"]="216x216" ["xxhdpi"]="324x324" ["xxxhdpi"]="432x432" )

for density in "${!sizes[@]}"; do
    size=${sizes[$density]}
    
    mkdir -p "app/src/main/res/mipmap-$density"
    convert light-logo.png -resize $size "app/src/main/res/mipmap-$density/ic_launcher_foreground.png"
    
    mkdir -p "app/src/main/res/mipmap-night-$density"
    convert dark-logo.png -resize $size "app/src/main/res/mipmap-night-$density/ic_launcher_foreground.png"
done

# Remove the one we mistakenly placed in drawable
rm -f app/src/main/res/drawable/ic_launcher_foreground.png
rm -f app/src/main/res/drawable-night/ic_launcher_foreground.png
