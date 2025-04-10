#!/bin/bash

# Start AcqServer
echo "Starting AcqServer..."
java -cp /app/out/artifacts/acq_3dsecure_jar/3dsecure.jar AcqServer &

# Start AcsServer
echo "Starting AcsServer..."
java -cp /app/out/artifacts/acs_3dsecure_jar/3dsecure.jar AcsServer &

# Start HttpsServer
echo "Starting HttpsServer..."
java -cp /app/out/artifacts/https_3dsecure_jar/3dsecure.jar HttpsServer &

# Wait for all background processes to finish
wait