#!/bin/bash

# Configuration
APP_NAME="lareferencia-embedding-service"

echo "--------------------------------------"
echo "Building $APP_NAME..."
echo "--------------------------------------"

# Ensure mvnw is executable
chmod +x mvnw

# Build the project
if ./mvnw clean package -DskipTests; then
    echo "--------------------------------------"
    echo "Build successful. Restarting service..."
    echo "--------------------------------------"
    
    # Restart the existing systemd service
    sudo systemctl restart $APP_NAME
    
    # Show status
    sudo systemctl status $APP_NAME --no-pager
else
    echo "--------------------------------------"
    echo "BUILD FAILED. Service not restarted."
    echo "--------------------------------------"
    exit 1
fi