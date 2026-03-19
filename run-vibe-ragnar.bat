@echo off
echo ============================================
echo   Starting vibe-ragnar MCP Server
echo ============================================
echo.
echo This may take a while on first run (downloading models, indexing codebase).
echo Leave this window open until it completes.
echo.

set REPO_PATH=C:\Users\colto\Documents\Projects\side-by-side

cd /d C:\Users\colto\Documents\vibe-ragnar

echo Starting server...
echo.
uv run python -m vibe_ragnar.server

echo.
echo ============================================
echo   Server exited.
echo ============================================
pause
