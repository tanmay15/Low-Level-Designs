// =============================================================================
// LLD: TIC-TAC-TOE
// Design doc (requirements, entities, relationships, patterns, extensibility):
//   → DESIGN.md  (keep both files in sync on any structural change)
// =============================================================================


// =============================================================================
// STEP 1 — REQUIREMENTS
// -----------------------------------------------------------------------------
// Functional:
//   1. Two players take turns placing their symbol (X or O) on a 3×3 board
//   2. A player wins by filling any row, column, or diagonal with their symbol
//   3. If all cells are filled with no winner → draw
//   4. An already-occupied cell cannot be played → invalid move rejected
//   5. No moves accepted after the game ends
//
// Non-Functional:
//   - Board size should be configurable (extensible to N×N)
//   - Win detection must work for any N (checks N in a row)
//   - Supporting more than 2 players should require minimal change
//
// Out of scope: AI opponent, game persistence, undo, networked multiplayer
// =============================================================================


// =============================================================================
// STEP 2 — ENUMS
// =============================================================================

enum PieceSymbol {
  X = "X",
  O = "O",
  EMPTY = ".",
}

enum GameStatus {
  IN_PROGRESS = "IN_PROGRESS",
  WIN = "WIN",
  DRAW = "DRAW",
}


// =============================================================================
// STEP 3 — CLASS DESIGN
// =============================================================================
// Entities:  Player, Board, Game
//
// Relationships:
//   Game  HAS-A (Composition)   Board    — board created inside game
//   Game  HAS-A (Aggregation)   Player[] — players passed in from outside
//
// Patterns:
//   State Machine → Game (IN_PROGRESS → WIN or DRAW)
//   No Strategy, No Observer, No Singleton
//
// No separate service class — Game IS the orchestrator (same as ParkingLot)
// =============================================================================


// ── Player ────────────────────────────────────────────────────────────────────

class Player {
  public name: string;
  public symbol: PieceSymbol;

  constructor(name: string, symbol: PieceSymbol) {
    this.name = name;
    this.symbol = symbol;
  }
}


// ── Board ─────────────────────────────────────────────────────────────────────
// Owns the 2D grid.
// Responsible for: placement validation, win detection, draw detection, display.
// Board does NOT know about players or game state — only the grid.

class Board {
  private grid: PieceSymbol[][];
  public size: number;

  constructor(size: number = 3) {
    this.size = size;
    // Initialize every cell to EMPTY
    this.grid = Array.from({ length: size }, () =>
      Array(size).fill(PieceSymbol.EMPTY)
    );
  }

  placePiece(row: number, col: number, symbol: PieceSymbol): void {
    if (row < 0 || row >= this.size || col < 0 || col >= this.size) {
      throw new Error(`Position (${row}, ${col}) is out of bounds`);
    }
    if (this.grid[row]![col] !== PieceSymbol.EMPTY) {
      throw new Error(`Position (${row}, ${col}) is already occupied`);
    }
    this.grid[row]![col] = symbol;
  }

  // Checks if the given symbol has won.
  // Works for any N×N board — loops size times in all directions.
  checkWin(symbol: PieceSymbol): boolean {
    // Check all rows
    for (let r = 0; r < this.size; r++) {
      let rowWin = true;
      for (let c = 0; c < this.size; c++) {
        if (this.grid[r]![c] !== symbol) { rowWin = false; break; }
      }
      if (rowWin) return true;
    }

    // Check all columns
    for (let c = 0; c < this.size; c++) {
      let colWin = true;
      for (let r = 0; r < this.size; r++) {
        if (this.grid[r]![c] !== symbol) { colWin = false; break; }
      }
      if (colWin) return true;
    }

    // Check main diagonal (top-left → bottom-right)
    let diagWin = true;
    for (let i = 0; i < this.size; i++) {
      if (this.grid[i]![i] !== symbol) { diagWin = false; break; }
    }
    if (diagWin) return true;

    // Check anti-diagonal (top-right → bottom-left)
    let antiDiagWin = true;
    for (let i = 0; i < this.size; i++) {
      if (this.grid[i]![this.size - 1 - i] !== symbol) { antiDiagWin = false; break; }
    }
    if (antiDiagWin) return true;

    return false;
  }

  isFull(): boolean {
    for (let r = 0; r < this.size; r++) {
      for (let c = 0; c < this.size; c++) {
        if (this.grid[r]![c] === PieceSymbol.EMPTY) return false;
      }
    }
    return true;
  }

  display(): void {
    console.log();
    for (let r = 0; r < this.size; r++) {
      console.log("  " + this.grid[r]!.join(" | "));
      if (r < this.size - 1) {
        console.log("  " + "-".repeat(this.size * 4 - 1));
      }
    }
    console.log();
  }
}


// ── Game ──────────────────────────────────────────────────────────────────────
// State machine: IN_PROGRESS → WIN or DRAW
// Orchestrates turn alternation and outcome determination.
// No separate service — Game IS the system (same reasoning as ParkingLot).

class Game {
  private players: Player[];
  private board: Board;
  private currentPlayerIndex: number;
  private status: GameStatus;

  constructor(players: Player[], boardSize: number = 3) {
    if (players.length < 2) throw new Error("Need at least 2 players");
    this.players = players;
    this.board = new Board(boardSize);
    this.currentPlayerIndex = 0;
    this.status = GameStatus.IN_PROGRESS;
  }

  getCurrentPlayer(): Player {
    return this.players[this.currentPlayerIndex] as Player;
  }

  isInProgress(): boolean {
    return this.status === GameStatus.IN_PROGRESS;
  }

  getStatus(): GameStatus {
    return this.status;
  }

  makeMove(row: number, col: number): void {
    if (!this.isInProgress()) {
      throw new Error(`Game is already over. Result: ${this.status}`);
    }

    const currentPlayer = this.getCurrentPlayer();

    // Board owns validation — throws if cell is occupied or out of bounds
    this.board.placePiece(row, col, currentPlayer.symbol);

    console.log(`${currentPlayer.name} (${currentPlayer.symbol}) plays → (${row}, ${col})`);
    this.board.display();

    // Check win after this move
    if (this.board.checkWin(currentPlayer.symbol)) {
      this.status = GameStatus.WIN;
      console.log(`** ${currentPlayer.name} wins! **\n`);
      return;
    }

    // Check draw
    if (this.board.isFull()) {
      this.status = GameStatus.DRAW;
      console.log("** It's a draw! **\n");
      return;
    }

    // Advance to next player — modulo makes it work for 2+ players automatically
    this.currentPlayerIndex = (this.currentPlayerIndex + 1) % this.players.length;
  }
}


// =============================================================================
// STEP 4 — DEMO
// =============================================================================

// ── Game 1: X wins via top row ─────────────────────────────────────────────
console.log("=== Game 1: X wins via top row ===\n");

const alice = new Player("Alice", PieceSymbol.X);
const bob = new Player("Bob", PieceSymbol.O);
const game1 = new Game([alice, bob]);

game1.makeMove(0, 0); // Alice X at (0,0)
game1.makeMove(1, 0); // Bob   O at (1,0)
game1.makeMove(0, 1); // Alice X at (0,1)
game1.makeMove(1, 1); // Bob   O at (1,1)
game1.makeMove(0, 2); // Alice X at (0,2) → wins top row

// Attempt move after game is over
try {
  game1.makeMove(2, 2);
} catch (e: any) {
  console.log(`[ERROR] ${e.message}\n`);
}


// ── Game 2: Draw ─────────────────────────────────────────────────────────────
console.log("=== Game 2: Draw ===\n");

const game2 = new Game([new Player("Alice", PieceSymbol.X), new Player("Bob", PieceSymbol.O)]);

// Final board — verified no winner:
// X | O | X
// X | X | O
// O | X | O
game2.makeMove(0, 0); // X
game2.makeMove(0, 1); // O
game2.makeMove(0, 2); // X
game2.makeMove(1, 2); // O
game2.makeMove(1, 0); // X
game2.makeMove(2, 0); // O
game2.makeMove(1, 1); // X
game2.makeMove(2, 2); // O
game2.makeMove(2, 1); // X → board full, no winner → draw


// ── Game 3: Invalid moves ────────────────────────────────────────────────────
console.log("=== Game 3: Invalid move handling ===\n");

const game3 = new Game([new Player("Alice", PieceSymbol.X), new Player("Bob", PieceSymbol.O)]);

game3.makeMove(1, 1); // Alice plays center

// Bob tries same cell
try {
  game3.makeMove(1, 1);
} catch (e: any) {
  console.log(`[ERROR] ${e.message}`);
}

// Out of bounds
try {
  game3.makeMove(5, 5);
} catch (e: any) {
  console.log(`[ERROR] ${e.message}`);
}


// ── Game 4: N×N board (4×4) ──────────────────────────────────────────────────
console.log("\n=== Game 4: 4×4 board — X wins via diagonal ===\n");

const game4 = new Game(
  [new Player("Alice", PieceSymbol.X), new Player("Bob", PieceSymbol.O)],
  4  // board size
);

game4.makeMove(0, 0); // X
game4.makeMove(0, 1); // O
game4.makeMove(1, 1); // X
game4.makeMove(0, 2); // O
game4.makeMove(2, 2); // X
game4.makeMove(0, 3); // O
game4.makeMove(3, 3); // X → main diagonal win (0,0)(1,1)(2,2)(3,3)
