# LLD: Chess

## Step 1 — Requirements

### Functional
1. Two players (WHITE and BLACK) alternate turns
2. Each piece type enforces its own movement rules
3. A move is valid only if: correct turn, own piece, valid destination, path clear, destination not own piece
4. Capturing: move to opponent's square removes their piece
5. Game ends on King capture (simplified win condition)

### Non-Functional
- `Piece` uses **abstract class** (Template Method) — shared state (color, position), unique behavior (`isValidMove`)
- `Board` is the single source of truth for piece locations
- Move validation is O(n) path check, O(1) for Knight

### Out of Scope
- Check / checkmate detection (would require simulating every possible move)
- Castling, en-passant, pawn promotion
- Draw by repetition or 50-move rule
- AI opponent

---

## Step 2 — Entities

| Entity     | Role                                                                  |
|------------|-----------------------------------------------------------------------|
| `Piece`    | Abstract base — color, position, `isValidMove()`                      |
| `King`, `Queen`, `Rook`, `Bishop`, `Knight`, `Pawn` | Concrete piece types |
| `Board`    | 8×8 grid of Piece, `movePiece()`, `isPathClear()`                    |
| `Player`   | Name + color                                                          |
| `Position` | (row, col) value object with `isValid()`                             |
| `Game`     | Orchestrates turns, validates, executes moves, tracks game status     |

---

## Step 3 — Class Design

### Why Abstract Class (not Interface) for `Piece`?
- All pieces **share state**: `color` and `position`
- If `Piece` were an interface, every concrete class (King, Queen, Rook...) would repeat these fields — violates DRY
- Only `isValidMove()` and `getType()` differ per class → they become `abstract` methods
- This is the **Template Method** pattern: base class defines the skeleton, subclasses fill in the details

### Movement Rules per Piece

| Piece   | Rule                                                                     | Path Check |
|---------|--------------------------------------------------------------------------|------------|
| Rook    | Same row OR same column, any distance                                    | Yes        |
| Bishop  | `\|Δrow\| == \|Δcol\|` (diagonal), any distance                         | Yes        |
| Queen   | Rook + Bishop combined                                                   | Yes        |
| King    | Any direction, exactly 1 square                                          | No         |
| Knight  | L-shape: (±1,±2) or (±2,±1) — **jumps over pieces**                    | No         |
| Pawn    | Forward 1 (or 2 from start), captures diagonally, blocked by pieces     | Partial    |

### `Board.isPathClear(from, to)`
- Determines step direction: `signum(Δrow)`, `signum(Δcol)`
- Steps from `from+step` to `to-step` checking for any non-null piece
- Knight skips this — it jumps regardless

### `Game.makeMove()` validation chain
```
1. Game is IN_PROGRESS?
2. Correct turn (color matches currentTurn)?
3. Piece exists at `from`?
4. Piece belongs to the moving player?
5. piece.isValidMove(to, board) → true?
6. Execute: board.movePiece(from, to)
7. Check: if captured piece is King → game over
8. Switch currentTurn
```

### Design Patterns
| Pattern            | Where                                        | Why                                        |
|--------------------|----------------------------------------------|--------------------------------------------|
| **Abstract Class** | `Piece` → concrete piece subclasses          | Shared state + per-type move behavior      |
| **Template Method**| `Piece.isValidMove()` as abstract method     | Consistent call site, unique implementation|

---

## Step 4 — How It Differs from Other Problems

| Feature             | Chess                                    | Other Games (Tic-Tac-Toe)               |
|---------------------|------------------------------------------|-----------------------------------------|
| Polymorphism        | Heavy — 6 different piece types          | None — single piece type                |
| Board               | 8×8, heterogeneous pieces                | N×N, uniform pieces                     |
| Move validation     | Per-piece rules + path check             | Just empty cell check                   |
| State machine       | In `Game` (turn, game status)            | In `Game` (current player, winner)      |
| Abstract class need | Strong — shared Piece fields             | No — no class hierarchy                 |

---

## Step 5 — Extensibility
- **Check/Checkmate**: After each move, verify opponent's King has no valid escape → set CHECKMATE
- **Castling**: Special King move — validate rook path clear, King not in check
- **En-passant**: Store `lastMove`; pawn can capture diagonally if last move was a 2-step pawn
- **Pawn Promotion**: When pawn reaches rank 8/1, allow replacement with Queen/Rook/etc
- **AI opponent**: Minimax with alpha-beta pruning on `Game.makeMove()`

---

## Quick Recall
1. `Piece` is an **abstract class** (not interface) because all pieces share `color` and `position` state
2. `isValidMove(to, board)` is the only abstract method — each piece overrides it
3. `Board.isPathClear()` uses `signum` to step direction-agnostically between two squares
4. Knight is the only piece that **jumps** — no `isPathClear` needed
5. `Game` handles ALL validation before delegating to `board.movePiece()`
