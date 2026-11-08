param([string]$Root = "app/src/main/java")
# 清理 Gradle/Kotlin 缓存（CI 幻影错误根治）：由 workflow 在 setup-gradle 之后调用
$dirs = @(
  "$env:HOME/.gradle/caches/journal-1",
  "$env:HOME/.gradle/caches/build-cache-1",
  "$env:HOME/.gradle/caches/8.13.2",
  "$env:HOME/.gradle/caches/transforms-4"
)
foreach ($d in $dirs) {
  if (Test-Path $d) { Remove-Item -Recurse -Force $d; Write-Output "removed $d" }
}
Write-Output "cache nuked"
