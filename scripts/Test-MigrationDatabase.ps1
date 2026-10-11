[CmdletBinding()]
param([ValidateSet('DataModelTest', 'FixtureIntegrationTest', 'AuthenticationIntegrationTest', 'TimetableImportIntegrationTest', 'CourseOfferingQueryIntegrationTest', 'CourseOfferingSameScheduleRegressionTest', 'CourseOfferingDetailIntegrationTest', 'TeacherComparisonIntegrationTest', 'LookupIntegrationTest', 'SupervisionCoverageIntegrationTest', 'Sprint1VerticalAcceptanceE2ETest', 'DemoBootstrapIntegrationTest', 'ClassroomAnalysisIntegrationTest', 'ClassroomAnalysisIntegrationTest#liveSyntheticReportUsesAliyunOnce', 'ClassroomAnalysisIntegrationTest#retainedSyntheticReportAuditRehearsal', 'ClassroomModuleOrchestrationIntegrationTest#explicitlyConfiguredRealModulesProcessOneSyntheticMedia', 'ClassroomModuleOrchestrationIntegrationTest#supervisorRebuildsRetryableFailedEnvelopeWithNewRequestKeyAndPreservedAudit', 'ClassroomPaidTestBudgetIntegrationTest')][string]$TestClass = 'DataModelTest')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot
$workRoot = Join-Path $projectRoot '.work'
$settings = Get-Content (Join-Path $workRoot 'dev-environment.json') -Raw | ConvertFrom-Json
$mysqlClient = Join-Path $settings.mysqlBin 'mysql.exe'
$clientOptions = Join-Path $workRoot 'mysql-client.cnf'
function Query([string]$sql) {
    $value = & $mysqlClient "--defaults-file=$clientOptions" --batch --skip-column-names -e $sql
    if ($LASTEXITCODE -ne 0) { throw 'Migration test database query failed.' }
    return ($value -join "`n").Trim()
}
$actualDirectory = [IO.Path]::GetFullPath((Query 'SELECT @@datadir')).TrimEnd('\','/')
$expectedDirectory = [IO.Path]::GetFullPath((Join-Path $workRoot 'mysql-dev')).TrimEnd('\','/')
if ($actualDirectory -ne $expectedDirectory) { throw 'Not the project isolated MySQL instance; test refused.' }
if ((Query 'SELECT VERSION()') -notmatch '^8\.0\.44(?:\D|$)') { throw 'Expected local MySQL 8.0.44.' }
if ((Query 'SELECT @@port') -ne [string]$settings.port) { throw 'Local database port mismatch.' }
if ($settings.DB_USERNAME -ne 'ai_teaching') { throw 'Unexpected development account; test refused.' }

# Infrastructure provisioning only. All tables are created by the production Flyway resources.
# A fresh random schema on every invocation makes the acceptance repeatable without deleting data.
$schema = 'ai_teaching_migration_' + [guid]::NewGuid().ToString('N')
Query "CREATE DATABASE $schema CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci" | Out-Null
# Escape underscores in the GRANT database pattern so this is a single-schema grant.
$grantSchema = $schema.Replace('_', '\_')
Query ('GRANT ALL PRIVILEGES ON `' + $grantSchema + '`.* TO ''ai_teaching''@''127.0.0.1''') | Out-Null
$keys = @('MIGRATION_TEST_URL', 'MIGRATION_TEST_USERNAME', 'MIGRATION_TEST_PASSWORD')
$previous = @{}
foreach ($key in $keys) { $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process') }
try {
    $env:MIGRATION_TEST_URL = "jdbc:mysql://127.0.0.1:$($settings.port)/${schema}?sslMode=DISABLED&allowPublicKeyRetrieval=true"
    $env:MIGRATION_TEST_USERNAME = [string]$settings.DB_USERNAME
    $env:MIGRATION_TEST_PASSWORD = [string]$settings.DB_PASSWORD
    Write-Output "Testing production migrations on fresh schema: $schema"
    & mvn -B -ntp -f (Join-Path $projectRoot 'backend/pom.xml') '-DmysqlMigration=true' "-Dtest=$TestClass" test
    if ($LASTEXITCODE -ne 0) { throw "Migration test failed; schema retained: $schema" }
    Write-Output "PASS: fresh migration, validation and repeat execution; test schema retained: $schema"
} finally {
    foreach ($key in $keys) { [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process') }
}
