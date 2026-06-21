# LLD: Cricket Scoreboard (Live Score System)

## Step 1 — Requirements

### Functional
1. Two teams play a 2-innings match
2. Ball-by-ball tracking: runs scored, wickets, extras (wide, no-ball)
3. Batsman stats updated: runs, balls faced, fours, sixes, out status
4. Bowler stats updated: balls bowled, runs conceded, wickets taken
5. Live scoreboard fans out to all subscribed displays after every ball (Observer)
6. Innings ends when all wickets fall
7. Match result determined after both innings

### Non-Functional
- Observer fan-out: every ball update triggers all registered `ScoreDisplay` subscribers
- Ball log is append-only — immutable audit trail of every delivery

### Out of Scope
- DLS (Duckworth-Lewis) method
- Super over, tie-breaker rules
- Powerplay and field restrictions
- Multi-format (T20 / ODI / Test) rules engine

---

## Step 2 — Entities

| Entity          | Role                                                              |
|-----------------|-------------------------------------------------------------------|
| `CricketPlayer` | Dual stats: batting (runs, balls, 4s, 6s) + bowling (overs, runs, wickets) |
| `CricketTeam`   | List of players; `getPlayer(id)` for O(n) lookup by ID           |
| `Ball`          | Immutable delivery record — batsman, bowler, runs, outcome, note |
| `Innings`       | Accumulates balls, totalRuns, wickets, ballCount                 |

---

## Step 3 — Class Design

### Attributes

#### `CricketPlayer`
| Attribute       | Type    | Notes                                      |
|-----------------|---------|--------------------------------------------|
| `runsScored`    | int     | Batting stat                               |
| `ballsFaced`    | int     | Does NOT increment on wide                 |
| `fours`, `sixes`| int     | Boundary tracking                          |
| `isOut`         | boolean | Set on WICKET outcome                      |
| `ballsBowled`   | int     | Does NOT increment on wide/no-ball         |
| `runsConceded`  | int     | Includes extras                            |
| `wicketsTaken`  | int     | Bowling wickets                            |

#### `Ball` (Immutable delivery record)
| Attribute    | Type          | Notes                                      |
|--------------|---------------|--------------------------------------------|
| `batsmanId`  | String        |                                            |
| `bowlerId`   | String        |                                            |
| `runs`       | int           | Runs off this delivery                     |
| `outcome`    | `BallOutcome` | RUNS / WICKET / WIDE / NO_BALL             |
| `note`       | String        | "FOUR", "SIX", "BOWLED", "CAUGHT" etc      |

#### `Innings`
| Attribute    | Type         | Notes                                       |
|--------------|--------------|---------------------------------------------|
| `balls`      | `List<Ball>` | Append-only delivery log                    |
| `totalRuns`  | int          | Including extras                            |
| `wickets`    | int          |                                             |
| `ballCount`  | int          | Legal deliveries only (not wide/no-ball)    |

**`getScore()`** → `"totalRuns/wickets (overs.ballsInOver ov)"` e.g. `"245/6 (42.3 ov)"`

#### `MatchService` — Key Methods
| Method                                              | Notes                                  |
|-----------------------------------------------------|----------------------------------------|
| `startMatch()`                                      | Creates innings1, sets status          |
| `startSecondInnings()`                              | Creates innings2, announces target     |
| `playBall(batsmanId, bowlerId, runs, outcome, note)`| Core method — updates all stats + notifies observers |
| `endInnings()`                                      | Notifies displays, sets INNINGS_BREAK  |
| `subscribe(display)`                                | Observer registration                  |
| `printScoreboard()`                                 | Console summary of both innings        |

### Observer Pattern — ScoreDisplay
```
ScoreDisplay (interface)
  ├── onBallPlayed(ball, innings, battingTeam)   ← after every delivery
  ├── onInningsEnd(innings, battingTeam)
  └── onMatchEnd(winnerId, margin)

ConsoleScoreDisplay  ← prints to terminal
// extensible: TVGraphicsDisplay, MobileAppDisplay, etc.
```
- Viewer is the observer here — same fan-out pattern as **FB Live Comments**
- `MatchService` holds `List<ScoreDisplay> displays` and iterates to notify all

### `playBall()` execution flow
```
1. Record Ball in innings.balls (append-only)
2. Update innings: totalRuns, wickets, ballCount (wide/no-ball don't count)
3. Update batsman: runs, ballsFaced, fours, sixes, isOut
4. Update bowler: runsConceded, ballsBowled, wicketsTaken
5. Fan-out: for each display → display.onBallPlayed(ball, innings, battingTeam)
6. Check: wickets >= maxWickets → endInnings()
7. Check: 2nd innings total > 1st innings total → endMatch()
```

---

## Step 4 — How It Differs from Other Problems

| Feature          | Cricket Scoreboard                  | FB Live Comments                    |
|------------------|-------------------------------------|-------------------------------------|
| Observer trigger | Every ball (playBall)               | Every comment (postComment)         |
| Observer target  | ScoreDisplay (display screen)       | Viewer (end user watching)          |
| Fan-out          | To all subscribed displays          | To all joined viewers of a stream   |
| State machine    | MatchStatus (NOT_STARTED → COMPLETED)| StreamStatus (LIVE → ENDED)        |

---

## Step 5 — Extensibility
- **Over limit**: Add `maxOvers` to `Innings`, end innings when `ballCount == maxOvers * 6`
- **Multiple formats**: T20 = 20 overs, ODI = 50, Test = unlimited wickets over days
- **Wagon wheel**: Track `(row, col)` of each shot to `Ball` → visualize on field map
- **Commentary**: Add `CommentaryDisplay implements ScoreDisplay` — generates text commentary per ball
- **DLS**: Add run-rate and target recalculation in `endInnings()` for rain interruptions

---

## Quick Recall
1. `Ball` is **immutable** and append-only — never delete or modify history
2. `ballCount` excludes wide and no-ball — only legal deliveries advance the over
3. Observer pattern: `playBall()` → all `ScoreDisplay.onBallPlayed()` — same as FB Live fan-out
4. `CricketPlayer` has **dual stats** — one object for both batting and bowling
5. Win condition in 2nd innings: batting team's total exceeds 1st innings total on any ball
