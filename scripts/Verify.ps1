$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot
Push-Location $projectRoot
try {
    & mvn -B -ntp -f backend/pom.xml test
    if ($LASTEXITCODE -ne 0) { throw 'Maven test failed.' }
    & mvn -B -ntp -f backend/pom.xml package
    if ($LASTEXITCODE -ne 0) { throw 'Maven package failed.' }
    Push-Location frontend
    try {
        & npm ci
        if ($LASTEXITCODE -ne 0) { throw 'npm ci failed.' }
        & npm run typecheck
        if ($LASTEXITCODE -ne 0) { throw 'Frontend typecheck failed.' }
        & npm test
        if ($LASTEXITCODE -ne 0) { throw 'Frontend tests failed.' }
        & npm run build
        if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
    } finally { Pop-Location }
} finally { Pop-Location }
