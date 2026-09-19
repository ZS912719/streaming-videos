# Streaming Videos Solver (Hash Code 2017)

本项目使用 Java 17 实现 Google Hash Code 2017 `Streaming Videos` 问题的一版启发式求解器。

## 数学模型

令二元变量 `x[c,v]` 表示视频 `v` 是否放入缓存 `c`。每个缓存满足容量约束：

```text
sum(size[v] * x[c,v]) <= cacheCapacity
```

对于终端 `e` 对视频 `v` 的请求，实际延迟是数据中心延迟与所有可用缓存延迟中的最小值。目标是最大化：

```text
sum(requestCount[e,v] * (dataCenterLatency[e] - bestLatency[e,v]))
```

该问题同时具有多背包和最大覆盖特征。本实现采用动态边际收益密度贪心：

```text
marginalGain(c,v)
  = sum(requestCount[e,v] * max(0, currentBestLatency[e,v] - cacheLatency[e,c]))

priority(c,v) = marginalGain(c,v) / videoSize[v]
```

放置一个视频后，受影响请求的当前最佳延迟会下降，其他候选的收益也会下降。求解器使用懒更新优先队列重新计算队首候选，避免重复计算已经被更快缓存覆盖的收益。

## 模块说明

- `Main`：命令行入口，串联解析、求解、校验、评分和输出。
- `InputParser`：高速读取数据集，并合并重复的 `(终端, 视频)` 请求。
- `ProblemInstance`：保存视频、终端、缓存连接及请求等问题数据。
- `GreedySolver`：生成稀疏候选，并执行动态边际收益密度贪心。
- `Solution`：保存每个缓存中的视频集合。
- `SolutionValidator`：检查视频 ID、重复项和缓存容量。
- `ScoreCalculator`：按官方公式计算最终得分。
- `SolutionWriter`：按比赛规定格式输出非空缓存。

所有请求次数、收益和评分中间量均使用 `long`，防止大数据集整数溢出。

## 编译

PowerShell：

```powershell
New-Item -ItemType Directory -Force out | Out-Null
javac -encoding UTF-8 -d out (Get-ChildItem -Recurse src/main/java/*.java)
```

## 运行

```powershell
java -cp out com.hashcode.streaming.Main input.in output.out
```

程序会把方案写入 `output.out`，并在标准错误中输出方案得分和缓存使用情况，因此不会污染比赛输出文件。
