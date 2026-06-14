// =============================================================================
// LLD: TIC-TAC-TOE — Java (interview format)
// Design doc (requirements, entities, relationships, patterns, extensibility):
//   → DESIGN.md  (keep both files in sync on any structural change)
// =============================================================================
// STEP 1 — REQUIREMENTS
// -----------------------------------------------------------------------------
// Functional:
//   1. Two players take turns placing their piece on the board
//   2. A player wins if they fill any row, column, or diagonal with their symbol
//   3. Game ends in a draw if the board is full and no winner
//   4. Invalid moves (out of bounds, cell taken) are rejected
//   5. No further moves allowed after the game ends
//
// Non-Functional:
//   - Board size N is configurable (not hardcoded to 3x3)
//   - Clear state machine: ONGOING → WIN or DRAW
//   - Board is fully encapsulated; Game drives the loop
//
// Out of scope: AI opponent, scoring/tournament, online multiplayer
// =============================================================================

import java.util.*;


// =============================================================================
// STEP 2 — ENUMS
// =============================================================================

// Using constructor + toString override so display shows "X", "O", "." instead of enum names
enum PieceSymbol {
    X("X"), O("O"), EMPTY(".");

    private final String display;

    PieceSymbol(String display) {
        this.display = display;
    }

    @Override
    public String toString() {
        return display;
    }
}

enum GameStatus { ONGOING, WIN, DRAW }


// =============================================================================
// STEP 3 — CLASS DESIGN
// =============================================================================
// Entities:  Player, Board, Game
//
// Relationships:
//   Game    HAS-A (Composition)   Board
//   Game    HAS-A (Aggregation)   Player[]
//   Board   HAS-A (Composition)   PieceSymbol[][] (2D grid)
//   Game    IS-A  State Machine   (ONGOING → WIN | DRAW)
// =============================================================================


// ── Player ────────────────────────────────────────────────────────────────────

class Player {
    public String name;
    public PieceSymbol symbol;

    public Player(String name, PieceSymbol symbol) {
        this.name = name;
        this.symbol = symbol;
    }
}


// ── Board ─────────────────────────────────────────────────────────────────────
// Owns the 2D grid. Responsible for placement, display, win-check, full-check.
// Game delegates all grid operations to Board.

class Board {
    private int size;
    private PieceSymbol[][] grid;

    public Board(int size) {
        this.size = size;
        this.grid = new PieceSymbol[size][size];
        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {
                grid[r][c] = PieceSymbol.EMPTY;
            }
        }
    }

    public boolean isValidMove(int row, int col) {
        return row >= 0 && row < size && col >= 0 && col < size && grid[row][col] == PieceSymbol.EMPTY;
    }

    public void place(int row, int col, PieceSymbol symbol) {
        grid[row][col] = symbol;
    }

    public boolean checkWin(PieceSymbol symbol) {
        // Check all rows
        for (int r = 0; r < size; r++) {
            boolean win = true;
            for (int c = 0; c < size; c++) {
                if (grid[r][c] != symbol) { win = false; break; }
            }
            if (win) return true;
        }
        // Check all columns
        for (int c = 0; c < size; c++) {
            boolean win = true;
            for (int r = 0; r < size; r++) {
                if (grid[r][c] != symbol) { win = false; break; }
            }
            if (win) return true;
        }
        // Check top-left → bottom-right diagonal
        boolean diag1 = true;
        for (int i = 0; i < size; i++) {
            if (grid[i][i] != symbol) { diag1 = false; break; }
        }
        if (diag1) return true;
        // Check top-right → bottom-left diagonal
        boolean diag2 = true;
        for (int i = 0; i < size; i++) {
            if (grid[i][size - 1 - i] != symbol) { diag2 = false; break; }
        }
        return diag2;
    }

    public boolean isFull() {
        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {
                if (grid[r][c] == PieceSymbol.EMPTY) return false;
            }
        }
        return true;
    }

    public void print() {
        System.out.println();
        for (int r = 0; r < size; r++) {
            StringBuilder row = new StringBuilder();
            for (int c = 0; c < size; c++) {
                if (c > 0) row.append(" | ");
                row.append(grid[r][c]);
            }
            System.out.println("  " + row);
            if (r < size - 1) {
                System.out.println("  " + "-".repeat(size * 4 - 3));
            }
        }
        System.out.println();
    }
}


// ── Game ──────────────────────────────────────────────────────────────────────
// State machine: ONGOING → WIN | DRAW
// Orchestrates the game loop: turn management, move validation, win/draw detection.

class Game {
    private Board board;
    private List<Player> players;
    private int currentPlayerIndex;
    private GameStatus status;
    private Player winner;

    public Game(Player player1, Player player2, int boardSize) {
        this.board = new Board(boardSize);
        this.players = new ArrayList<>(Arrays.asList(player1, player2));
        this.currentPlayerIndex = 0;
        this.status = GameStatus.ONGOING;
        this.winner = null;
    }

    public Player getCurrentPlayer() {
        return players.get(currentPlayerIndex);
    }

    public GameStatus makeMove(int row, int col) {
        if (status != GameStatus.ONGOING) {
            System.out.println("[GAME OVER] Game has already ended with status: " + status);
            return status;
        }

        Player player = getCurrentPlayer();

        if (!board.isValidMove(row, col)) {
            System.out.println("[INVALID] " + player.name + " → (" + row + "," + col + ") is taken or out of bounds");
            return status;
        }

        board.place(row, col, player.symbol);
        System.out.println("[MOVE] " + player.name + " (" + player.symbol + ") → (" + row + "," + col + ")");

        if (board.checkWin(player.symbol)) {
            status = GameStatus.WIN;
            winner = player;
        } else if (board.isFull()) {
            status = GameStatus.DRAW;
        } else {
            currentPlayerIndex = (currentPlayerIndex + 1) % players.size();
        }

        return status;
    }

    public void printResult() {
        board.print();
        if (status == GameStatus.WIN) {
            System.out.println("🏆 " + winner.name + " wins!");
        } else if (status == GameStatus.DRAW) {
            System.out.println("🤝 It's a draw!");
        } else {
            System.out.println("Game is still ongoing...");
        }
    }
}


// =============================================================================
// STEP 4 — DEMO
// public class name must match filename: TicTacToeSolution.java
// =============================================================================

public class TicTacToeSolution {
    public static void main(String[] args) {
        System.out.println("=== Tic-Tac-Toe Demo ===\n");

        // --- Game 1: Alice wins diagonally ---
        System.out.println("── Game 1: Alice wins (diagonal) ──");
        Player alice = new Player("Alice", PieceSymbol.X);
        Player bob   = new Player("Bob",   PieceSymbol.O);
        Game game1 = new Game(alice, bob, 3);

        game1.makeMove(0, 0); // Alice X
        game1.makeMove(0, 1); // Bob   O
        game1.makeMove(1, 1); // Alice X
        game1.makeMove(0, 2); // Bob   O
        game1.makeMove(2, 2); // Alice X — diagonal win

        game1.printResult();

        // Attempt move after game over
        game1.makeMove(1, 0);

        System.out.println();

        // --- Game 2: Draw ---
        System.out.println("── Game 2: Draw ──");
        Player p1 = new Player("Alice", PieceSymbol.X);
        Player p2 = new Player("Bob",   PieceSymbol.O);
        Game game2 = new Game(p1, p2, 3);

        // X O X
        // X X O
        // O X O  → draw (no winner)
        game2.makeMove(0, 0); // Alice X
        game2.makeMove(0, 1); // Bob   O
        game2.makeMove(0, 2); // Alice X
        game2.makeMove(2, 0); // Bob   O
        game2.makeMove(1, 0); // Alice X
        game2.makeMove(1, 2); // Bob   O
        game2.makeMove(1, 1); // Alice X
        game2.makeMove(2, 2); // Bob   O
        game2.makeMove(2, 1); // Alice X — board full, no winner → draw

        game2.printResult();

        System.out.println();

        // --- Game 3: Invalid move ---
        System.out.println("── Game 3: Invalid move ──");
        Player p3 = new Player("Alice", PieceSymbol.X);
        Player p4 = new Player("Bob",   PieceSymbol.O);
        Game game3 = new Game(p3, p4, 3);

        game3.makeMove(1, 1); // Alice X
        game3.makeMove(1, 1); // Bob tries same cell → invalid
        game3.makeMove(0, 5); // Bob tries out of bounds → invalid

        game3.printResult();

        System.out.println();

        // --- Game 4: 4x4 board ---
        System.out.println("── Game 4: 4×4 board ──");
        Player p5 = new Player("Alice", PieceSymbol.X);
        Player p6 = new Player("Bob",   PieceSymbol.O);
        Game game4 = new Game(p5, p6, 4);

        game4.makeMove(0, 0); // Alice X
        game4.makeMove(1, 0); // Bob   O
        game4.makeMove(0, 1); // Alice X
        game4.makeMove(1, 1); // Bob   O
        game4.makeMove(0, 2); // Alice X
        game4.makeMove(1, 2); // Bob   O
        game4.makeMove(0, 3); // Alice X — wins row 0

        game4.printResult();
    }
}
