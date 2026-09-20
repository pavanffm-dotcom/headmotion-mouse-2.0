@echo off
title HeadMotion Mouse Simulator
echo =======================================================
echo   Starting Local Server for Webcam Permissions...
echo =======================================================
echo.
start "" "http://localhost:8000/test_simulator/index.html"
python -m http.server 8000
pause
