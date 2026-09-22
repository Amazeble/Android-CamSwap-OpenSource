import os

path = "app/src/main/java/io/github/zensu357/camswap/HookMain.java"
if not os.path.exists(path):
    print(f"File not found: {path}")
else:
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()
    
    # Fix the wrong package name
    content = content.replace("android.graphics.Image.Plane", "android.media.Image.Plane")
    
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
        
    print("✅ Fixed android.graphics.Image.Plane -> android.media.Image.Plane")
    print("Now run: ./gradlew clean assembleDebug")