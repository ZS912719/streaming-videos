param(
    [Parameter(Mandatory=$true)][string]$JudgeSource,
    [string]$Archive,
    [string]$Inputs='tmp/promo-inputs',
    [ValidateSet('all','fresh','incumbent','validate','summary')][string]$Phase='all'
)
$ErrorActionPreference='Stop'
# The integrated tree has new registry/diagnostic sources. Keep the historical
# study's strict whole-tree admission check by replaying its pinned commit.
$options=@{JudgeSource=$JudgeSource;Inputs=$Inputs;Phase=$Phase}
if ($Archive) { $options.Archive=$Archive }
& (Join-Path $PSScriptRoot 'replay-frozen-v2.ps1') @options
