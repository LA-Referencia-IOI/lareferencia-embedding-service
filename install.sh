#!/bin/bash

# 1. Extract metadata from pom.xml (Ignoring the <parent> block)
APP_NAME=$(sed -n '/<parent>/,/<\/parent>/d; s/.*<artifactId>\(.*\)<\/artifactId>.*/\1/p' pom.xml | head -n 1)
VERSION=$(sed -n '/<parent>/,/<\/parent>/d; s/.*<version>\(.*\)<\/version>.*/\1/p' pom.xml | head -n 1)

# Fallback: if project version is missing
if [ -z "$VERSION" ]; then
    VERSION=$(grep -A 10 "</parent>" pom.xml | grep -m 1 "<version>" | sed 's/.*<version>\(.*\)<\/version>.*/\1/')
fi

CURRENT_DIR=$(pwd)
BUILD_DIR="$CURRENT_DIR/target"
VERSIONED_JAR="$BUILD_DIR/$APP_NAME-$VERSION.jar"
STABLE_JAR="$BUILD_DIR/$APP_NAME-current.jar" # This is our stable pointer
SERVICE_FILE="/etc/systemd/system/$APP_NAME.service"

echo "Building $APP_NAME version $VERSION..."

# 2. Build the application
chmod +x mvnw
if ./mvnw clean package -DskipTests; then

    # 3. Validation & Stable Link Creation
    if [ ! -f "$VERSIONED_JAR" ]; then
        echo "Error: JAR file not found at $VERSIONED_JAR."
        exit 1
    fi

    # Create an absolute symlink (the 'stable' file)
    ln -sf "$VERSIONED_JAR" "$STABLE_JAR"
    echo "Stable link updated: $STABLE_JAR -> $VERSIONED_JAR"

    # 4. Service Creation/Update
    # We only create/update this if it doesn't exist, as the path is now stable
    if [ ! -f "$SERVICE_FILE" ]; then
        echo "Creating fresh systemd service file..."
        sudo bash -c "cat <<EOT > $SERVICE_FILE
[Unit]
Description=La Referencia Embedding Service
After=syslog.target network.target

[Service]
User=$USER
WorkingDirectory=$CURRENT_DIR
ExecStart=/usr/bin/java -jar $STABLE_JAR
SuccessExitStatus=143
Restart=always
RestartSec=10
StandardOutput=syslog
StandardError=syslog
SyslogIdentifier=$APP_NAME

[Install]
WantedBy=multi-user.target
EOT"
        sudo systemctl daemon-reload
        sudo systemctl enable "$APP_NAME"
    fi

    echo "Restarting service..."
    sudo systemctl restart "$APP_NAME"

    echo "------------------------------------------------"
    echo "Success! Service is running using the stable link."
    echo "Currently pointing to: $(basename "$VERSIONED_JAR")"
    echo "------------------------------------------------"
else
    echo "Build failed. Service was not updated."
    exit 1
fi