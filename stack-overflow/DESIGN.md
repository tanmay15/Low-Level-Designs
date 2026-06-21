# LLD: Stack Overflow (Q&A + Voting + Reputation)

## Step 1 — Requirements

### Functional
1. Users post questions tagged with topics
2. Users answer questions (not their own)
3. Vote UP (+1) or DOWN (-1) on questions and answers — one vote per user per post
4. Question author accepts one answer as the "best answer"
5. Search questions by tag: O(1) via tag index
6. Reputation: +10 upvote received, -2 downvote received, +15 answer accepted

### Non-Functional
- Duplicate vote prevention: `Map<voterId+targetId, voteValue>`
- Tag-based search without scanning all questions: `Map<tag, List<questionId>>`

### Out of Scope
- Comments on answers
- Bounties, badges, privilege levels
- Question editing / revision history
- Moderation / flagging
- Full-text search

---

## Step 2 — Entities

| Entity     | Role                                                        |
|------------|-------------------------------------------------------------|
| `SOUser`   | Has reputation; gains/loses rep on votes and accepted answers |
| `Question` | Has votes, tags, list of answers, one accepted answer       |
| `Answer`   | Belongs to a question; can be accepted; has vote score      |

---

## Step 3 — Class Design

### Attributes

#### `SOUser`
| Attribute    | Type   | Notes                                       |
|--------------|--------|---------------------------------------------|
| `reputation` | int    | Updated on vote received or answer accepted  |

#### `Question`
| Attribute          | Type           | Notes                                      |
|--------------------|----------------|--------------------------------------------|
| `votes`            | int            | Aggregate score (+1/-1 accumulator)         |
| `tags`             | `List<String>` | Used for tag index                          |
| `answers`          | `List<Answer>` | All answers for this question               |
| `acceptedAnswerId` | String         | null until an answer is accepted            |

#### `Answer`
| Attribute    | Type    | Notes                                       |
|--------------|---------|---------------------------------------------|
| `votes`      | int     | Aggregate score                             |
| `isAccepted` | boolean | Set by question owner via `acceptAnswer()`  |

#### `StackOverflowService` — Key Data Structures
| Structure                                  | Purpose                                  |
|--------------------------------------------|------------------------------------------|
| `Map<String, SOUser> users`               | O(1) user lookup                          |
| `Map<String, Question> questions`         | O(1) question lookup                      |
| `Map<String, Answer> answers`             | O(1) answer lookup                        |
| `Map<String, List<String>> tagIndex`      | tag → List of questionIds (search by tag) |
| `Map<String, Integer> voteTracker`        | `voterId:targetId` → vote value (dup prevention) |

### Vote Logic — Detailed
```
Key = voterId + ":" + targetId

Case 1: existingVote == newValue  → Retract vote (voting same way twice = cancel)
Case 2: existingVote != 0        → Undo old vote, apply new vote
Case 3: existingVote == 0        → Fresh vote
```

### Reputation Rules
| Action                    | Author gains |
|---------------------------|--------------|
| Question upvoted          | +10          |
| Question downvoted        | -2           |
| Answer upvoted            | +10          |
| Answer downvoted          | -2           |
| Answer accepted           | +15          |

### Key Methods
| Method                                    | Notes                                    |
|-------------------------------------------|------------------------------------------|
| `postQuestion(authorId, title, body, tags)`| Indexes question by each tag             |
| `postAnswer(authorId, questionId, body)`  | Cannot answer own question               |
| `voteQuestion(voterId, qId, +1/-1)`       | Updates question.votes + author.reputation |
| `voteAnswer(voterId, aId, +1/-1)`         | Updates answer.votes + author.reputation  |
| `acceptAnswer(questionOwnerId, answerId)` | Only question author can accept; +15 rep for answerer |
| `searchByTag(tag)`                        | O(1) lookup in `tagIndex`                |

---

## Step 4 — How It Differs from Other Problems

| Feature             | Stack Overflow                      | Social Media Feed                    |
|---------------------|-------------------------------------|--------------------------------------|
| Core content unit   | Question + Answer hierarchy         | Post (flat)                          |
| Voting              | Up/Down with deduplication          | Like (only up, Set-based)            |
| Reputation system   | Explicit numeric scoring            | No reputation concept                |
| Search              | Tag-based index (O(1))              | Follow-based pull (O(followers))     |
| Acceptance          | One answer can be "accepted"        | Not applicable                       |

---

## Step 5 — Extensibility
- **Comments**: Add `Comment` entity with `targetId` (question or answer), store in lists
- **Badges**: Triggered by reputation thresholds or specific actions (e.g. 100 answers)
- **Bounties**: `Bounty` entity on a question, automatically awarded on acceptance or expiry
- **Ranking**: Sort answers by `votes desc`, `isAccepted first` for display
- **Full-text search**: Maintain inverted index on words in question titles/bodies

---

## Quick Recall
1. `voteTracker` key = `voterId:targetId` — prevents duplicate votes in O(1)
2. `tagIndex` = `Map<tag, List<questionId>>` — built at `postQuestion()` time for O(1) tag search
3. Reputation is on `SOUser`, not on Question/Answer — centralized and easy to query
4. Vote retraction: same vote twice cancels the original (realistic behavior)
5. `acceptAnswer()` validates the caller is the **question's author** — access control in service layer
