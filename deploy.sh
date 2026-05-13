#!/bin/bash

# 1. Extract metadata from pom.xml (same logic as install script)
APP_NAME=$(sed -n '/<parent>/,/<\/parent>/d; s/.*<artifactId>\(.*\)<\/artifactId>.*/\1/p' pom.xml | head -n 1)
VERSION=$(sed -n '/<parent>/,/<\/parent>/d; s/.*<version>\(.*\)<\/version>.*/\1/p' pom.xml | head -n 1)

if [ -z "$VERSION" ]; then
    VERSION=$(grep -A 10 "</parent>" pom.xml | grep -m 1 "<version>" | sed 's/.*<version>\(.*\)<\/version>.*/\1/')
fi

CURRENT_DIR=$(pwd)
BUILD_DIR="$CURRENT_DIR/target"
VERSIONED_JAR="$BUILD_DIR/$APP_NAME-$VERSION.jar"
STABLE_JAR="$BUILD_DIR/$APP_NAME-current.jar"

echo "--------------------------------------"
echo "Re-deploying $APP_NAME ($VERSION)..."
echo "--------------------------------------"

chmod +x mvnw

# 2. Build the project
if ./mvnw clean package -DskipTests; then
    
    # 3. Validation
    if [ ! -f "$VERSIONED_JAR" ]; then
        echo "Error: JAR file not found at $VERSIONED_JAR."
        exit 1
    fi

    # 4. CRITICAL: Update the stable symlink
    # Without this, the service points to a non-existent or old file
    ln -sf "$VERSIONED_JAR" "$STABLE_JAR"
    echo "Updated stable link to point to newest build."

    echo "--------------------------------------"
    echo "Restarting service..."
    echo "--------------------------------------"
    
    # Restart the existing systemd service
    sudo systemctl restart "$APP_NAME"
    
    # Show status
    sleep 1
    sudo systemctl status "$APP_NAME" --no-pager
else
    echo "--------------------------------------"
    echo "BUILD FAILED. Service not restarted."
    echo "--------------------------------------"
    exit 1
fi