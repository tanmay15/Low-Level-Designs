// =============================================================================
// LLD: CRICKET SCOREBOARD (Live Score System)
// Design doc: DESIGN.md
// =============================================================================
// STEP 1 — REQUIREMENTS
// Functional:
//   1. Two teams play a match with two innings
//   2. Ball-by-ball tracking: runs scored, wickets, extras (wide, no-ball)
//   3. Batsman and bowler stats updated on each ball
//   4. Live scoreboard displays update on every ball (Observer)
//   5. Innings ends when all wickets fall or over limit reached
//   6. Match result determined by runs comparison
//
// Non-Functional:
//   - Observer: ScoreDisplay notified on every ball → fan-out like FB Live
//   - Ball-by-ball log is append-only (audit trail)
//
// Out of scope: DLS method, super over, powerplay restrictions,
//   field restrictions, ICC match formats beyond 2-innings
//
// KEY INSIGHT — Observer pattern:
//   playBall() records the delivery, updates all stats, then notifies
//   all ScoreDisplay observers. Same fan-out as FB Live Comments.
//   ScoreDisplay is an interface — console display, TV graphics, mobile app
//   can all receive the same update without changing MatchService.
// =============================================================================

import java.util.*;


// =============================================================================
// ENUMS
// =============================================================================

enum BallOutcome { RUNS, WICKET, WIDE, NO_BALL }
enum MatchStatus { NOT_STARTED, IN_PROGRESS, INNINGS_BREAK, COMPLETED }


// =============================================================================
// ENTITIES
// =============================================================================

// ── Player ────────────────────────────────────────────────────────────────────
class CricketPlayer {
    public String id;
    public String name;
    // Batting stats
    public int    runsScored;
    public int    ballsFaced;
    public int    fours;
    public int    sixes;
    public boolean isOut;
    // Bowling stats
    public int    ballsBowled;
    public int    runsConceded;
    public int    wicketsTaken;

    public CricketPlayer(String id, String name) {
        this.id   = id;
        this.name = name;
    }

    public double battingAverage() {
        return ballsFaced == 0 ? 0 : (double) runsScored / ballsFaced * 100;
    }

    public String battingLine() {
        return String.format("%-12s %3d(%d)  4s:%-2d 6s:%-2d  SR:%.1f%s",
                name, runsScored, ballsFaced, fours, sixes, battingAverage(),
                isOut ? " OUT" : " *");
    }

    public String bowlingLine() {
        double overs = ballsBowled / 6.0;
        return String.format("%-12s %.1f ov  %d runs  %d wkts",
                name, overs, runsConceded, wicketsTaken);
    }
}

// ── Team ──────────────────────────────────────────────────────────────────────
class CricketTeam {
    public String               id;
    public String               name;
    public List<CricketPlayer>  players;

    public CricketTeam(String id, String name, List<CricketPlayer> players) {
        this.id      = id;
        this.name    = name;
        this.players = players;
    }

    public CricketPlayer getPlayer(String playerId) {
        for (CricketPlayer p : players) if (p.id.equals(playerId)) return p;
        return null;
    }
}

// ── Ball ──────────────────────────────────────────────────────────────────────
// Immutable record of one delivery — the append-only log of an innings.
class Ball {
    public String      batsmanId;
    public String      bowlerId;
    public int         runs;
    public BallOutcome outcome;
    public String      note;    // e.g. "FOUR", "SIX", "BOWLED"

    public Ball(String batsmanId, String bowlerId, int runs, BallOutcome outcome, String note) {
        this.batsmanId = batsmanId;
        this.bowlerId  = bowlerId;
        this.runs      = runs;
        this.outcome   = outcome;
        this.note      = note;
    }

    @Override
    public String toString() {
        return String.format("Ball[batsman=%s bowler=%s %d runs %s %s]",
                batsmanId, bowlerId, runs, outcome, note == null ? "" : note);
    }
}

// ── Innings ───────────────────────────────────────────────────────────────────
class Innings {
    public String          battingTeamId;
    public List<Ball>      balls    = new ArrayList<>();
    public int             totalRuns;
    public int             wickets;
    public int             ballCount; // legal deliveries only (not wide/no-ball)

    public Innings(String battingTeamId) {
        this.battingTeamId = battingTeamId;
    }

    public int overs() { return ballCount / 6; }
    public int ballsInOver() { return ballCount % 6; }

    public String getScore() {
        return totalRuns + "/" + wickets + " (" + overs() + "." + ballsInOver() + " ov)";
    }
}


// =============================================================================
// OBSERVER PATTERN — ScoreDisplay
// =============================================================================

interface ScoreDisplay {
    void onBallPlayed(Ball ball, Innings innings, CricketTeam battingTeam);
    void onInningsEnd(Innings innings, CricketTeam battingTeam);
    void onMatchEnd(String winnerId, int margin);
}

// Console display — shows live score after each delivery
class ConsoleScoreDisplay implements ScoreDisplay {
    private String name;

    public ConsoleScoreDisplay(String name) { this.name = name; }

    @Override
    public void onBallPlayed(Ball ball, Innings innings, CricketTeam battingTeam) {
        System.out.printf("  [%s] %s: %d runs | Score: %s%s%n",
                name, battingTeam.name, ball.runs, innings.getScore(),
                ball.note != null ? " (" + ball.note + ")" : "");
    }

    @Override
    public void onInningsEnd(Innings innings, CricketTeam battingTeam) {
        System.out.println("  [" + name + "] INNINGS END: "
                + battingTeam.name + " — " + innings.getScore());
    }

    @Override
    public void onMatchEnd(String winnerId, int margin) {
        System.out.println("  [" + name + "] MATCH RESULT: Winner=" + winnerId
                + " by " + margin + " runs");
    }
}


// =============================================================================
// MATCH SERVICE
// =============================================================================

class MatchService {
    private CricketTeam         team1;
    private CricketTeam         team2;
    private Innings             innings1;
    private Innings             innings2;
    private Innings             currentInnings;
    private MatchStatus         status;
    private List<ScoreDisplay>  displays = new ArrayList<>();
    private int                 maxWickets;

    public MatchService(CricketTeam team1, CricketTeam team2, int maxWickets) {
        this.team1       = team1;
        this.team2       = team2;
        this.maxWickets  = maxWickets;
        this.status      = MatchStatus.NOT_STARTED;
    }

    public void subscribe(ScoreDisplay display) { displays.add(display); }

    // ── Match lifecycle ───────────────────────────────────────────────────────

    public void startMatch() {
        innings1       = new Innings(team1.id);  // team1 bats first
        currentInnings = innings1;
        status         = MatchStatus.IN_PROGRESS;
        System.out.println("[MATCH] Started: " + team1.name + " vs " + team2.name);
        System.out.println("[MATCH] " + team1.name + " batting first");
    }

    public void startSecondInnings() {
        if (innings2 != null) return;
        innings2       = new Innings(team2.id);
        currentInnings = innings2;
        status         = MatchStatus.IN_PROGRESS;
        System.out.println("\n[MATCH] 2nd Innings: " + team2.name
                + " need " + (innings1.totalRuns + 1) + " to win");
    }

    // ── Play a ball ───────────────────────────────────────────────────────────
    // Core method: record delivery, update stats, notify observers.

    public void playBall(String batsmanId, String bowlerId,
                         int runs, BallOutcome outcome, String note) {
        if (status != MatchStatus.IN_PROGRESS)
            throw new RuntimeException("Match not in progress");

        CricketTeam battingTeam  = getTeam(currentInnings.battingTeamId);
        CricketTeam fieldingTeam = (battingTeam == team1) ? team2 : team1;

        CricketPlayer batsman = battingTeam.getPlayer(batsmanId);
        CricketPlayer bowler  = fieldingTeam.getPlayer(bowlerId);

        Ball ball = new Ball(batsmanId, bowlerId, runs, outcome, note);
        currentInnings.balls.add(ball);

        // Update innings totals
        currentInnings.totalRuns += runs;
        if (outcome == BallOutcome.WICKET) {
            currentInnings.wickets++;
        }
        if (outcome != BallOutcome.WIDE && outcome != BallOutcome.NO_BALL) {
            currentInnings.ballCount++;
        }

        // Update batsman stats
        if (batsman != null) {
            batsman.runsScored += runs;
            if (outcome != BallOutcome.WIDE) batsman.ballsFaced++;
            if (runs == 4) batsman.fours++;
            if (runs == 6) batsman.sixes++;
            if (outcome == BallOutcome.WICKET) batsman.isOut = true;
        }

        // Update bowler stats
        if (bowler != null) {
            bowler.runsConceded += runs;
            if (outcome != BallOutcome.WIDE && outcome != BallOutcome.NO_BALL)
                bowler.ballsBowled++;
            if (outcome == BallOutcome.WICKET) bowler.wicketsTaken++;
        }

        // Notify all displays (Observer fan-out)
        for (ScoreDisplay display : displays) {
            display.onBallPlayed(ball, currentInnings, battingTeam);
        }

        // Check innings end condition
        if (currentInnings.wickets >= maxWickets) {
            endInnings();
        }

        // Check if 2nd innings and batting team has passed target
        if (currentInnings == innings2 && innings2.totalRuns > innings1.totalRuns) {
            endMatch();
        }
    }

    public void endInnings() {
        CricketTeam battingTeam = getTeam(currentInnings.battingTeamId);
        for (ScoreDisplay d : displays) d.onInningsEnd(currentInnings, battingTeam);
        status = MatchStatus.INNINGS_BREAK;
        System.out.println("[MATCH] Innings end: " + battingTeam.name
                + " " + currentInnings.getScore());
    }

    private void endMatch() {
        status = MatchStatus.COMPLETED;
        String winnerId;
        int    margin;

        if (innings1 == null || innings2 == null) return;

        if (innings2.totalRuns > innings1.totalRuns) {
            // Team2 won — margin = wickets remaining
            winnerId = team2.id;
            margin   = maxWickets - innings2.wickets;
        } else {
            // Team1 won — margin = run difference
            winnerId = team1.id;
            margin   = innings1.totalRuns - innings2.totalRuns;
        }

        for (ScoreDisplay d : displays) d.onMatchEnd(winnerId, margin);
        System.out.println("[MATCH] COMPLETED. Winner: " + getTeam(winnerId).name);
    }

    private CricketTeam getTeam(String teamId) {
        return team1.id.equals(teamId) ? team1 : team2;
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    public void printScoreboard() {
        System.out.println("\n── SCOREBOARD ──");
        if (innings1 != null) {
            System.out.println(getTeam(innings1.battingTeamId).name + ": " + innings1.getScore());
            for (CricketPlayer p : getTeam(innings1.battingTeamId).players) {
                if (p.ballsFaced > 0) System.out.println("  " + p.battingLine());
            }
            System.out.println("  Bowling:");
            for (CricketPlayer p : getTeam(innings2 != null ? innings2.battingTeamId : team2.id).players) {
                if (p.ballsBowled > 0) System.out.println("  " + p.bowlingLine());
            }
        }
        if (innings2 != null) {
            System.out.println(getTeam(innings2.battingTeamId).name + ": " + innings2.getScore());
        }
    }
}


// =============================================================================
// DEMO
// =============================================================================

public class CricketScoreboardSolution {
    public static void main(String[] args) {
        System.out.println("=== Cricket Scoreboard Demo ===\n");

        // ── Setup teams ───────────────────────────────────────────────────────
        List<CricketPlayer> indiaPlayers = new ArrayList<>(Arrays.asList(
                new CricketPlayer("IND1", "Rohit"),
                new CricketPlayer("IND2", "Virat"),
                new CricketPlayer("IND3", "Gill"),
                new CricketPlayer("IND-B1", "Bumrah"),
                new CricketPlayer("IND-B2", "Shami")
        ));

        List<CricketPlayer> ausPlayers = new ArrayList<>(Arrays.asList(
                new CricketPlayer("AUS1", "Warner"),
                new CricketPlayer("AUS2", "Smith"),
                new CricketPlayer("AUS3", "Maxwell"),
                new CricketPlayer("AUS-B1", "Starc"),
                new CricketPlayer("AUS-B2", "Hazlewood")
        ));

        CricketTeam india     = new CricketTeam("IND", "India",     indiaPlayers);
        CricketTeam australia = new CricketTeam("AUS", "Australia", ausPlayers);

        MatchService match = new MatchService(india, australia, 10);

        // Subscribe displays (Observer)
        match.subscribe(new ConsoleScoreDisplay("LIVE"));
        System.out.println();

        // ── 1st Innings: India bats ────────────────────────────────────────────
        System.out.println("── 1st Innings: India batting ──");
        match.startMatch();
        System.out.println();

        match.playBall("IND1", "AUS-B1", 0,  BallOutcome.RUNS,   null);
        match.playBall("IND1", "AUS-B1", 4,  BallOutcome.RUNS,   "FOUR");
        match.playBall("IND2", "AUS-B1", 6,  BallOutcome.RUNS,   "SIX");
        match.playBall("IND1", "AUS-B2", 1,  BallOutcome.RUNS,   null);
        match.playBall("IND2", "AUS-B2", 0,  BallOutcome.WICKET, "CAUGHT");
        match.playBall("IND3", "AUS-B2", 2,  BallOutcome.RUNS,   null);
        match.playBall("IND1", "AUS-B1", 4,  BallOutcome.RUNS,   "FOUR");
        match.playBall("IND3", "AUS-B1", 0,  BallOutcome.WICKET, "BOWLED");
        // ... more balls to end innings
        match.playBall("IND1", "AUS-B2", 0,  BallOutcome.WICKET, "LBW");
        match.playBall("IND-B1","AUS-B1",3,  BallOutcome.RUNS,   null);
        match.playBall("IND-B1","AUS-B2",0,  BallOutcome.WICKET, "CAUGHT");
        match.playBall("IND-B2","AUS-B1",2,  BallOutcome.RUNS,   null);
        match.playBall("IND-B2","AUS-B2",0,  BallOutcome.WICKET, "BOWLED");
        match.endInnings();
        System.out.println();

        // ── 2nd Innings: Australia chases ─────────────────────────────────────
        System.out.println("── 2nd Innings: Australia chasing ──");
        match.startSecondInnings();
        System.out.println();

        match.playBall("AUS1", "IND-B1", 6,  BallOutcome.RUNS,   "SIX");
        match.playBall("AUS1", "IND-B1", 4,  BallOutcome.RUNS,   "FOUR");
        match.playBall("AUS2", "IND-B2", 0,  BallOutcome.WICKET, "BOWLED");
        match.playBall("AUS1", "IND-B1", 4,  BallOutcome.RUNS,   "FOUR");
        match.playBall("AUS3", "IND-B2", 6,  BallOutcome.RUNS,   "SIX");
        match.playBall("AUS1", "IND-B1", 0,  BallOutcome.WICKET, "CAUGHT");
        // Australia passes India's total
        match.playBall("AUS3", "IND-B2", 6,  BallOutcome.RUNS,   "SIX - WIN!");

        System.out.println();
        match.printScoreboard();
    }
}
