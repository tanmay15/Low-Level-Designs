// =============================================================================
// LLD: CHESS
// Design doc: DESIGN.md
// =============================================================================
// STEP 1 — REQUIREMENTS
// Functional:
//   1. Two players (WHITE and BLACK) take alternate turns
//   2. Each piece type has its own movement rules (isValidMove)
//   3. A move is valid only if: correct turn, own piece, valid destination,
//      path is clear (where applicable), and destination is not own piece
//   4. Capturing: moving to a square occupied by opponent removes their piece
//   5. Game ends on CHECKMATE or STALEMATE (simplified: track manually)
//
// Non-Functional:
//   - Piece uses ABSTRACT CLASS (Template Method) — all pieces share color
//     and position; only isValidMove() differs per piece type
//   - Board is the source of truth for piece positions
//
// Out of scope: check detection, castling, en-passant, pawn promotion,
//   draw by repetition, AI opponent
//
// KEY DESIGN DECISION — Abstract class over interface for Piece:
//   All pieces share state (color, position) → abstract class.
//   If it were an interface, every piece would repeat these fields.
//   isValidMove() is the only differentiating behavior → abstract method.
// =============================================================================

import java.util.*;


// =============================================================================
// ENUMS
// =============================================================================

enum Color      { WHITE, BLACK }
enum PieceType  { KING, QUEEN, ROOK, BISHOP, KNIGHT, PAWN }
enum GameStatus { IN_PROGRESS, WHITE_WINS, BLACK_WINS, DRAW }


// =============================================================================
// VALUE OBJECT — Position
// =============================================================================

class Position {
    public int row; // 0-7 (0 = rank 1, 7 = rank 8)
    public int col; // 0-7 (0 = file a, 7 = file h)

    public Position(int row, int col) {
        this.row = row;
        this.col = col;
    }

    public boolean isValid() { return row >= 0 && row < 8 && col >= 0 && col < 8; }

    @Override
    public String toString() {
        return "" + (char)('a' + col) + (row + 1); // e.g. "e2"
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Position)) return false;
        Position p = (Position) o;
        return row == p.row && col == p.col;
    }
}


// =============================================================================
// ABSTRACT CLASS — Piece (Template Method)
// =============================================================================
// All pieces share: color, position, getType().
// Each piece defines its own isValidMove() rule.

abstract class Piece {
    public Color    color;
    public Position position;

    public Piece(Color color, Position position) {
        this.color    = color;
        this.position = position;
    }

    public abstract PieceType getType();

    // Core rule: is moving from current position to `to` a legal move?
    // Board is passed to check if path is clear and what's at destination.
    public abstract boolean isValidMove(Position to, Board board);

    // Helper: is destination occupied by own piece? (universal rejection rule)
    protected boolean isOwnPiece(Position to, Board board) {
        Piece target = board.getPiece(to);
        return target != null && target.color == this.color;
    }

    @Override
    public String toString() { return color.name().charAt(0) + "" + getType().name().charAt(0); }
}

// ── Rook ──────────────────────────────────────────────────────────────────────
class Rook extends Piece {
    public Rook(Color color, Position pos) { super(color, pos); }

    @Override public PieceType getType() { return PieceType.ROOK; }

    @Override
    public boolean isValidMove(Position to, Board board) {
        if (isOwnPiece(to, board)) return false;
        // Must move along same row OR same column
        if (position.row != to.row && position.col != to.col) return false;
        return board.isPathClear(position, to);
    }
}

// ── Bishop ────────────────────────────────────────────────────────────────────
class Bishop extends Piece {
    public Bishop(Color color, Position pos) { super(color, pos); }

    @Override public PieceType getType() { return PieceType.BISHOP; }

    @Override
    public boolean isValidMove(Position to, Board board) {
        if (isOwnPiece(to, board)) return false;
        // Must move diagonally: |Δrow| == |Δcol|
        if (Math.abs(position.row - to.row) != Math.abs(position.col - to.col)) return false;
        return board.isPathClear(position, to);
    }
}

// ── Queen ─────────────────────────────────────────────────────────────────────
// Queen = Rook + Bishop combined
class Queen extends Piece {
    public Queen(Color color, Position pos) { super(color, pos); }

    @Override public PieceType getType() { return PieceType.QUEEN; }

    @Override
    public boolean isValidMove(Position to, Board board) {
        if (isOwnPiece(to, board)) return false;
        boolean straightLine = (position.row == to.row || position.col == to.col);
        boolean diagonal     = Math.abs(position.row - to.row) == Math.abs(position.col - to.col);
        if (!straightLine && !diagonal) return false;
        return board.isPathClear(position, to);
    }
}

// ── King ──────────────────────────────────────────────────────────────────────
class King extends Piece {
    public King(Color color, Position pos) { super(color, pos); }

    @Override public PieceType getType() { return PieceType.KING; }

    @Override
    public boolean isValidMove(Position to, Board board) {
        if (isOwnPiece(to, board)) return false;
        // Moves exactly 1 square in any direction
        int dr = Math.abs(position.row - to.row);
        int dc = Math.abs(position.col - to.col);
        return dr <= 1 && dc <= 1 && (dr + dc > 0);
    }
}

// ── Knight ────────────────────────────────────────────────────────────────────
class Knight extends Piece {
    public Knight(Color color, Position pos) { super(color, pos); }

    @Override public PieceType getType() { return PieceType.KNIGHT; }

    @Override
    public boolean isValidMove(Position to, Board board) {
        if (isOwnPiece(to, board)) return false;
        // L-shape: (±1,±2) or (±2,±1). Knight JUMPS — no path check needed.
        int dr = Math.abs(position.row - to.row);
        int dc = Math.abs(position.col - to.col);
        return (dr == 2 && dc == 1) || (dr == 1 && dc == 2);
    }
}

// ── Pawn ──────────────────────────────────────────────────────────────────────
class Pawn extends Piece {
    public Pawn(Color color, Position pos) { super(color, pos); }

    @Override public PieceType getType() { return PieceType.PAWN; }

    @Override
    public boolean isValidMove(Position to, Board board) {
        if (isOwnPiece(to, board)) return false;

        int direction = (color == Color.WHITE) ? 1 : -1;  // WHITE moves up, BLACK moves down
        int dr        = to.row - position.row;
        int dc        = Math.abs(to.col - position.col);

        // Forward 1 square — destination must be empty
        if (dc == 0 && dr == direction && board.getPiece(to) == null) return true;

        // Forward 2 squares from starting row — both squares must be empty
        int startRow = (color == Color.WHITE) ? 1 : 6;
        if (dc == 0 && dr == 2 * direction && position.row == startRow
                && board.getPiece(to) == null
                && board.getPiece(new Position(position.row + direction, position.col)) == null)
            return true;

        // Diagonal capture — destination must have opponent piece
        if (dc == 1 && dr == direction && board.getPiece(to) != null
                && board.getPiece(to).color != color) return true;

        return false;
    }
}


// =============================================================================
// BOARD
// =============================================================================

class Board {
    private Piece[][] grid = new Piece[8][8];

    // Initialize standard chess starting position
    public void setup() {
        // WHITE pieces (row 0 and 1)
        grid[0][0] = new Rook(Color.WHITE,   new Position(0, 0));
        grid[0][1] = new Knight(Color.WHITE, new Position(0, 1));
        grid[0][2] = new Bishop(Color.WHITE, new Position(0, 2));
        grid[0][3] = new Queen(Color.WHITE,  new Position(0, 3));
        grid[0][4] = new King(Color.WHITE,   new Position(0, 4));
        grid[0][5] = new Bishop(Color.WHITE, new Position(0, 5));
        grid[0][6] = new Knight(Color.WHITE, new Position(0, 6));
        grid[0][7] = new Rook(Color.WHITE,   new Position(0, 7));
        for (int c = 0; c < 8; c++) grid[1][c] = new Pawn(Color.WHITE, new Position(1, c));

        // BLACK pieces (row 7 and 6)
        grid[7][0] = new Rook(Color.BLACK,   new Position(7, 0));
        grid[7][1] = new Knight(Color.BLACK, new Position(7, 1));
        grid[7][2] = new Bishop(Color.BLACK, new Position(7, 2));
        grid[7][3] = new Queen(Color.BLACK,  new Position(7, 3));
        grid[7][4] = new King(Color.BLACK,   new Position(7, 4));
        grid[7][5] = new Bishop(Color.BLACK, new Position(7, 5));
        grid[7][6] = new Knight(Color.BLACK, new Position(7, 6));
        grid[7][7] = new Rook(Color.BLACK,   new Position(7, 7));
        for (int c = 0; c < 8; c++) grid[6][c] = new Pawn(Color.BLACK, new Position(6, c));
    }

    public Piece getPiece(Position pos) {
        if (!pos.isValid()) return null;
        return grid[pos.row][pos.col];
    }

    // Execute the move (caller must have validated it)
    public void movePiece(Position from, Position to) {
        Piece piece = grid[from.row][from.col];
        if (piece == null) return;
        grid[to.row][to.col]   = piece;
        grid[from.row][from.col] = null;
        piece.position = to;
    }

    // Check if all squares between from and to are empty (for Rook, Bishop, Queen)
    // Knight skips this check — it jumps.
    public boolean isPathClear(Position from, Position to) {
        int dr = Integer.signum(to.row - from.row);
        int dc = Integer.signum(to.col - from.col);

        int r = from.row + dr;
        int c = from.col + dc;

        while (r != to.row || c != to.col) {
            if (grid[r][c] != null) return false; // blocked
            r += dr;
            c += dc;
        }
        return true;
    }

    // Print board state for debugging
    public void print() {
        System.out.println("  a  b  c  d  e  f  g  h");
        for (int r = 7; r >= 0; r--) {
            System.out.print((r + 1) + " ");
            for (int c = 0; c < 8; c++) {
                Piece p = grid[r][c];
                System.out.print(p == null ? " . " : " " + p + " ");
            }
            System.out.println(r + 1);
        }
        System.out.println("  a  b  c  d  e  f  g  h");
    }
}


// =============================================================================
// ENTITIES
// =============================================================================

class Player {
    public String id;
    public String name;
    public Color  color;

    public Player(String id, String name, Color color) {
        this.id    = id;
        this.name  = name;
        this.color = color;
    }
}


// =============================================================================
// GAME
// =============================================================================
// Orchestrates turns, validates moves, updates board, tracks game status.

class Game {
    private Board      board;
    private Player     player1;  // WHITE
    private Player     player2;  // BLACK
    private Color      currentTurn;
    private GameStatus status;
    private int        moveCount;

    public Game(Player player1, Player player2) {
        this.board       = new Board();
        this.player1     = player1;
        this.player2     = player2;
        this.currentTurn = Color.WHITE;   // WHITE always moves first
        this.status      = GameStatus.IN_PROGRESS;
        this.moveCount   = 0;
        board.setup();
    }

    // Returns true if move was made, false if invalid
    public boolean makeMove(Color color, Position from, Position to) {
        if (status != GameStatus.IN_PROGRESS) {
            System.out.println("  [GAME] Game is over: " + status);
            return false;
        }
        if (color != currentTurn) {
            System.out.println("  [GAME] Not your turn: " + color);
            return false;
        }
        if (!from.isValid() || !to.isValid()) {
            System.out.println("  [GAME] Invalid position");
            return false;
        }

        Piece piece = board.getPiece(from);
        if (piece == null) {
            System.out.println("  [GAME] No piece at " + from);
            return false;
        }
        if (piece.color != color) {
            System.out.println("  [GAME] That's not your piece");
            return false;
        }
        if (!piece.isValidMove(to, board)) {
            System.out.println("  [GAME] Invalid move for " + piece.getType()
                    + " from " + from + " to " + to);
            return false;
        }

        // Capture: check if opponent's King is captured (simplified win condition)
        Piece captured = board.getPiece(to);
        if (captured != null && captured.getType() == PieceType.KING) {
            board.movePiece(from, to);
            status = (color == Color.WHITE) ? GameStatus.WHITE_WINS : GameStatus.BLACK_WINS;
            moveCount++;
            System.out.println("  [GAME] " + piece + " " + from + " → " + to
                    + " captures KING! " + status);
            return true;
        }

        board.movePiece(from, to);
        moveCount++;

        String captureNote = (captured != null) ? " captures " + captured : "";
        System.out.println("  [GAME] " + piece + " " + from + " → " + to + captureNote);

        // Switch turn
        currentTurn = (currentTurn == Color.WHITE) ? Color.BLACK : Color.WHITE;
        return true;
    }

    public GameStatus getStatus() { return status; }

    public void printBoard() { board.print(); }
}


// =============================================================================
// DEMO
// =============================================================================

public class ChessSolution {
    public static void main(String[] args) {
        System.out.println("=== Chess Demo ===\n");

        Player white = new Player("P1", "Alice", Color.WHITE);
        Player black = new Player("P2", "Bob",   Color.BLACK);
        Game   game  = new Game(white, black);

        System.out.println("── Initial Board ──");
        game.printBoard();
        System.out.println();

        System.out.println("── Playing Moves ──");

        // Standard opening: e2→e4 (WHITE pawn), e7→e5 (BLACK pawn)
        game.makeMove(Color.WHITE, new Position(1, 4), new Position(3, 4)); // e2→e4
        game.makeMove(Color.BLACK, new Position(6, 4), new Position(4, 4)); // e7→e5

        // Knight moves: g1→f3 (WHITE), b8→c6 (BLACK)
        game.makeMove(Color.WHITE, new Position(0, 6), new Position(2, 5)); // Ng1→f3
        game.makeMove(Color.BLACK, new Position(7, 1), new Position(5, 2)); // Nb8→c6

        // Bishop: f1→c4 (WHITE)
        game.makeMove(Color.WHITE, new Position(0, 5), new Position(3, 2)); // Bf1→c4

        System.out.println();
        System.out.println("── Board after 5 moves ──");
        game.printBoard();
        System.out.println();

        // Invalid move attempts
        System.out.println("── Invalid Move Attempts ──");
        game.makeMove(Color.WHITE, new Position(7, 0), new Position(5, 0)); // BLACK's rook — wrong color
        game.makeMove(Color.WHITE, new Position(0, 3), new Position(5, 3)); // Queen blocked
        game.makeMove(Color.BLACK, new Position(4, 4), new Position(4, 4)); // same square

        System.out.println("\nGame status: " + game.getStatus());
    }
}
