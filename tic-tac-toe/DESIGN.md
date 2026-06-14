# LLD Design: Tic-Tac-Toe

> **Sync note:** Design companion to `tic-tac-toe.ts`. Keep both files in sync on any structural change.

---

## Step 1 — Requirements

### Functional
1. Two players take turns placing their symbol (X or O) on a 3×3 board
2. A player wins by filling any row, column, or diagonal with their symbol
3. If all cells are filled with no winner → draw
4. An already-occupied cell cannot be played — invalid move is rejected
5. No moves are accepted after the game ends (WIN or DRAW)

### Non-Functional
- Board size should be configurable (extensible to N×N)
- Win detection must work for any N (checks N in a row in all directions)
- Supporting more than 2 players should require minimal change

### Out of Scope
- AI opponent
- Game persistence
- Undo move
- Networked multiplayer

---

## Step 2 — Entities

| Noun | Becomes | Reason |
|---|---|---|
| PieceSymbol | Enum | Fixed set: X / O / EMPTY |
| GameStatus | Enum | Fixed states: IN_PROGRESS / WIN / DRAW |
| Player | Class | Has name + symbol — real-world actor |
| Board | Class | Owns the 2D grid, placement, win detection, display |
| Move | Not needed | Row + col + player passed directly to `makeMove()` |
| Game | Class (orchestrator) | State machine — manages turns and game outcome |

No separate service class. `Game` IS the system — same reasoning as `ParkingLot`.

---

## Step 3 — Class Design

---

### `Player`
- **Attributes:** `name: string`, `symbol: PieceSymbol`
- **Methods:** None
- **Note:** Pure data holder. Symbol is fixed at construction.

---

### `Board`
- **Attributes:**
  - `size: number` — public (configurable at construction)
  - `grid: PieceSymbol[][]` — private (2D grid, initialized to all EMPTY)
- **Methods:** `placePiece(row, col, symbol)`, `checkWin(symbol): boolean`, `isFull(): boolean`, `display(): void`
- **Key decisions:**
  - `placePiece()` throws if out of bounds or cell already occupied — Board owns all grid validation
  - `checkWin()` loops through all rows, columns, and both diagonals — works for any N×N size
  - `isFull()` signals draw condition — Game checks this after every move
  - Board knows nothing about Players, turns, or game state

---

### `Game` *(state machine + orchestrator)*
- **Attributes:**
  - `players: Player[]` — aggregation, passed in from outside
  - `board: Board` — composition, created inside Game
  - `currentPlayerIndex: number` — tracks whose turn it is
  - `status: GameStatus` — the state machine variable
- **Methods:** `makeMove(row, col)`, `getCurrentPlayer(): Player`, `isInProgress(): boolean`, `getStatus(): GameStatus`
- **State machine:**
  ```
  IN_PROGRESS + move + win condition  →  WIN   (terminal)
  IN_PROGRESS + move + board full     →  DRAW  (terminal)
  IN_PROGRESS + move + neither        →  IN_PROGRESS (next turn)
  WIN or DRAW + any move              →  throws Error
  ```
- **Turn alternation:** `currentPlayerIndex = (currentPlayerIndex + 1) % players.length`
  - Works for 2 players: alternates 0→1→0→1
  - Works for 3 players: cycles 0→1→2→0 with zero code change

---

## Step 4 — Relationships

| From | To | Type | Why |
|---|---|---|---|
| `Game` | `Board` | **Composition** | Board is created inside Game, no life outside |
| `Game` | `Player[]` | **Aggregation** | Players exist independently, passed to constructor |

Simplest relationship structure of all 6 problems. Two classes, one composition.

---

## Step 5 — Design Patterns

### State Machine → `Game`
- **States:** `IN_PROGRESS → WIN` or `IN_PROGRESS → DRAW` (both terminal)
- **Why:** Game has a clear lifecycle. Terminal states must block further moves.
- **How:** `makeMove()` checks `isInProgress()` first, throws if not. After placing, checks win then draw.
- **Interview line:** *"Game is a state machine. WIN and DRAW are terminal — any further move throws. This prevents invalid state silently creeping in."*

No Strategy, No Observer, No Singleton.

**Extensibility via Strategy (mention, don't implement):**
Win detection could be extracted into a `WinStrategy` interface. `Board.checkWin()` would delegate to the injected strategy. This enables Connect Four, custom board games, etc. without changing `Board`.

---

## Step 6 — Win Detection Algorithm

```
checkWin(symbol):

  // All rows — N iterations
  for r in 0..size:
    if grid[r][0] == grid[r][1] == ... == grid[r][N-1] == symbol → WIN

  // All columns — N iterations
  for c in 0..size:
    if grid[0][c] == grid[1][c] == ... == grid[N-1][c] == symbol → WIN

  // Main diagonal (top-left → bottom-right)
  if grid[0][0] == grid[1][1] == ... == grid[N-1][N-1] == symbol → WIN

  // Anti-diagonal (top-right → bottom-left)
  if grid[0][N-1] == grid[1][N-2] == ... == grid[N-1][0] == symbol → WIN

  return false
```

This generalises to any N×N naturally — just loop N times instead of 3.

---

## Step 7 — Extensibility

| Change Request | What changes |
|---|---|
| N×N board | Pass `size` to `Board` constructor. `checkWin()` already loops `size` times — zero change. |
| 3+ players | Add more `Player` objects to the `players[]` array. Turn rotation via modulo works automatically. |
| AI opponent | Create `AIPlayer extends Player` with `chooseMove(board): {row, col}`. Call in game loop. |
| Extract win logic | Create `WinStrategy` interface. `Board.checkWin()` delegates to injected strategy. |
| Undo last move | Add `moveHistory: {row, col, symbol}[]`. `undoMove()` pops last entry, resets that cell. |

---

## Quick Recall

```
Player: name, symbol (X / O)

Board (owns the grid):
  grid: PieceSymbol[][]   initialized to all EMPTY
  placePiece(row, col, symbol)  → throws if occupied or out of bounds
  checkWin(symbol)  → checks all rows, cols, both diagonals
  isFull()  → all cells filled → draw condition

Game (state machine):
  players[], board, currentPlayerIndex, status

makeMove(row, col):
  → placePiece on board (throws if invalid)
  → checkWin → if true → status = WIN, return
  → isFull  → if true → status = DRAW, return
  → currentPlayerIndex = (index + 1) % players.length

State machine: IN_PROGRESS → WIN or DRAW (both terminal)
Turn rotation: modulo — works for 2+ players automatically
Pattern: State Machine only
No service class — Game IS the orchestrator
```
