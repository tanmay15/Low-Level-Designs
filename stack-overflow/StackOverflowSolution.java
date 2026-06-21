// =============================================================================
// LLD: STACK OVERFLOW (Q&A + Voting + Reputation)
// Design doc: DESIGN.md
// =============================================================================
// STEP 1 — REQUIREMENTS
// Functional:
//   1. Users post questions with tags
//   2. Users post answers to questions
//   3. Vote UP or DOWN on questions and answers (one vote per user per post)
//   4. Question owner accepts one answer as the "best" answer
//   5. Search questions by tag
//   6. Reputation system: +10 for upvote received, -2 for downvote received,
//      +15 when your answer is accepted
//
// Non-Functional:
//   - Duplicate vote prevention: Map<voterId+targetId, voteValue>
//   - Tag index: Map<tag, List<questionId>> for O(1) tag search
//
// Out of scope: comments on answers, bounties, badges, question editing history,
//   moderation/flagging, full-text search
// =============================================================================

import java.util.*;


// =============================================================================
// ENTITIES
// =============================================================================

// ── SOUser ────────────────────────────────────────────────────────────────────
class SOUser {
    public String id;
    public String name;
    public int    reputation;

    public SOUser(String id, String name) {
        this.id         = id;
        this.name       = name;
        this.reputation = 0;
    }

    public void addReputation(int points) {
        reputation += points;
        System.out.println("  [REP] " + name + " reputation: " + reputation
                + " (" + (points > 0 ? "+" : "") + points + ")");
    }

    @Override
    public String toString() {
        return String.format("User[%s | %s | rep=%d]", id, name, reputation);
    }
}

// ── Question ──────────────────────────────────────────────────────────────────
class Question {
    public String       id;
    public String       authorId;
    public String       title;
    public String       body;
    public List<String> tags;
    public int          votes;            // aggregate vote score
    public List<Answer> answers;
    public String       acceptedAnswerId; // null until accepted
    public long         createdAt;

    public Question(String id, String authorId, String title, String body, List<String> tags) {
        this.id         = id;
        this.authorId   = authorId;
        this.title      = title;
        this.body       = body;
        this.tags       = tags;
        this.votes      = 0;
        this.answers    = new ArrayList<>();
        this.createdAt  = System.currentTimeMillis();
    }

    @Override
    public String toString() {
        return String.format("Q[%s] \"%s\" | votes=%d | answers=%d | tags=%s",
                id, title, votes, answers.size(), tags);
    }
}

// ── Answer ────────────────────────────────────────────────────────────────────
class Answer {
    public String id;
    public String questionId;
    public String authorId;
    public String body;
    public int    votes;
    public boolean isAccepted;
    public long   createdAt;

    public Answer(String id, String questionId, String authorId, String body) {
        this.id         = id;
        this.questionId = questionId;
        this.authorId   = authorId;
        this.body       = body;
        this.votes      = 0;
        this.isAccepted = false;
        this.createdAt  = System.currentTimeMillis();
    }

    @Override
    public String toString() {
        return String.format("A[%s] by=%s | votes=%d | accepted=%s | \"%s\"",
                id, authorId, votes, isAccepted, body.substring(0, Math.min(40, body.length())));
    }
}


// =============================================================================
// STACK OVERFLOW SERVICE
// =============================================================================

class StackOverflowService {
    private Map<String, SOUser>        users        = new HashMap<>();
    private Map<String, Question>      questions    = new HashMap<>();
    private Map<String, Answer>        answers      = new HashMap<>();
    private Map<String, List<String>>  tagIndex     = new HashMap<>(); // tag → List<questionId>
    private Map<String, Integer>       voteTracker  = new HashMap<>(); // voterId+targetId → value (+1/-1)

    private int qCounter = 0;
    private int aCounter = 0;

    // Reputation points
    private static final int REP_UPVOTE_RECEIVED   = 10;
    private static final int REP_DOWNVOTE_RECEIVED = -2;
    private static final int REP_ANSWER_ACCEPTED   = 15;

    // ── Registration ──────────────────────────────────────────────────────────

    public void registerUser(SOUser user) {
        users.put(user.id, user);
        System.out.println("[SO] Registered: " + user);
    }

    // ── Post question ─────────────────────────────────────────────────────────

    public Question postQuestion(String authorId, String title, String body, List<String> tags) {
        if (users.get(authorId) == null) throw new RuntimeException("User not found");

        String   qId      = "Q-" + (++qCounter);
        Question question = new Question(qId, authorId, title, body, tags);
        questions.put(qId, question);

        // Index by each tag for O(1) tag search
        for (String tag : tags) {
            tagIndex.computeIfAbsent(tag, k -> new ArrayList<>()).add(qId);
        }

        System.out.println("[SO] Question posted: " + question);
        return question;
    }

    // ── Post answer ───────────────────────────────────────────────────────────

    public Answer postAnswer(String authorId, String questionId, String body) {
        Question question = questions.get(questionId);
        if (question == null) throw new RuntimeException("Question not found");
        if (users.get(authorId) == null) throw new RuntimeException("User not found");
        if (question.authorId.equals(authorId))
            throw new RuntimeException("Cannot answer your own question");

        String aId    = "A-" + (++aCounter);
        Answer answer = new Answer(aId, questionId, authorId, body);
        question.answers.add(answer);
        answers.put(aId, answer);

        System.out.println("[SO] Answer posted: " + answer);
        return answer;
    }

    // ── Vote ──────────────────────────────────────────────────────────────────
    // value: +1 (upvote) or -1 (downvote)
    // Prevents duplicate votes. Allows vote reversal (same vote twice = cancel).

    public void voteQuestion(String voterId, String questionId, int value) {
        if (value != 1 && value != -1) throw new RuntimeException("Vote must be +1 or -1");
        Question question = questions.get(questionId);
        SOUser   author   = users.get(question.authorId);
        if (question == null || author == null) return;
        if (question.authorId.equals(voterId)) {
            System.out.println("  [SO] Cannot vote on your own question");
            return;
        }

        String key          = voterId + ":" + questionId;
        int    existingVote = voteTracker.getOrDefault(key, 0);

        if (existingVote == value) {
            // Same vote again → retract it
            question.votes -= value;
            author.addReputation(value == 1 ? -REP_UPVOTE_RECEIVED : -REP_DOWNVOTE_RECEIVED);
            voteTracker.remove(key);
            System.out.println("[SO] Vote retracted on " + questionId);
        } else {
            // Undo previous vote if any, apply new vote
            if (existingVote != 0) {
                question.votes -= existingVote;
                author.addReputation(existingVote == 1 ? -REP_UPVOTE_RECEIVED : -REP_DOWNVOTE_RECEIVED);
            }
            question.votes += value;
            author.addReputation(value == 1 ? REP_UPVOTE_RECEIVED : REP_DOWNVOTE_RECEIVED);
            voteTracker.put(key, value);
            System.out.println("[SO] " + voterId + " voted " + (value > 0 ? "↑" : "↓")
                    + " on " + questionId + " | total votes: " + question.votes);
        }
    }

    public void voteAnswer(String voterId, String answerId, int value) {
        if (value != 1 && value != -1) throw new RuntimeException("Vote must be +1 or -1");
        Answer answer = answers.get(answerId);
        SOUser author = users.get(answer.authorId);
        if (answer == null || author == null) return;
        if (answer.authorId.equals(voterId)) {
            System.out.println("  [SO] Cannot vote on your own answer");
            return;
        }

        String key          = voterId + ":" + answerId;
        int    existingVote = voteTracker.getOrDefault(key, 0);

        if (existingVote == value) {
            answer.votes -= value;
            author.addReputation(value == 1 ? -REP_UPVOTE_RECEIVED : -REP_DOWNVOTE_RECEIVED);
            voteTracker.remove(key);
            System.out.println("[SO] Answer vote retracted: " + answerId);
        } else {
            if (existingVote != 0) {
                answer.votes -= existingVote;
                author.addReputation(existingVote == 1 ? -REP_UPVOTE_RECEIVED : -REP_DOWNVOTE_RECEIVED);
            }
            answer.votes += value;
            author.addReputation(value == 1 ? REP_UPVOTE_RECEIVED : REP_DOWNVOTE_RECEIVED);
            voteTracker.put(key, value);
            System.out.println("[SO] " + voterId + " voted " + (value > 0 ? "↑" : "↓")
                    + " on answer " + answerId + " | total votes: " + answer.votes);
        }
    }

    // ── Accept answer ─────────────────────────────────────────────────────────
    // Only the question's author can accept an answer.

    public void acceptAnswer(String questionOwnerId, String answerId) {
        Answer   answer   = answers.get(answerId);
        Question question = questions.get(answer.questionId);

        if (answer == null || question == null) throw new RuntimeException("Not found");
        if (!question.authorId.equals(questionOwnerId))
            throw new RuntimeException("Only the question author can accept an answer");
        if (answer.isAccepted) {
            System.out.println("[SO] Answer already accepted");
            return;
        }

        // Un-accept previous if any
        if (question.acceptedAnswerId != null) {
            Answer prev = answers.get(question.acceptedAnswerId);
            if (prev != null) prev.isAccepted = false;
        }

        answer.isAccepted            = true;
        question.acceptedAnswerId    = answerId;

        // Reward answerer
        SOUser answerAuthor = users.get(answer.authorId);
        if (answerAuthor != null) answerAuthor.addReputation(REP_ANSWER_ACCEPTED);

        System.out.println("[SO] Answer accepted: " + answerId + " on question " + question.id);
    }

    // ── Search by tag ─────────────────────────────────────────────────────────

    public List<Question> searchByTag(String tag) {
        List<String>   qIds   = tagIndex.getOrDefault(tag, new ArrayList<>());
        List<Question> result = new ArrayList<>();
        for (String qId : qIds) result.add(questions.get(qId));
        System.out.println("[SO] Tag \"" + tag + "\" → " + result.size() + " questions");
        return result;
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    public void printQuestion(String questionId) {
        Question q = questions.get(questionId);
        System.out.println("  " + q);
        for (Answer a : q.answers) System.out.println("    " + a);
    }

    public void printUser(String userId) {
        System.out.println("  " + users.get(userId));
    }
}


// =============================================================================
// DEMO
// =============================================================================

public class StackOverflowSolution {
    public static void main(String[] args) {
        System.out.println("=== Stack Overflow Demo ===\n");

        StackOverflowService so = new StackOverflowService();

        so.registerUser(new SOUser("U1", "Alice"));
        so.registerUser(new SOUser("U2", "Bob"));
        so.registerUser(new SOUser("U3", "Charlie"));
        System.out.println();

        // ── Alice posts a question ─────────────────────────────────────────────
        System.out.println("── Alice Posts a Question ──");
        Question q1 = so.postQuestion("U1", "How does HashMap work in Java?",
                "I want to understand the internal working of HashMap.",
                Arrays.asList("java", "hashmap", "data-structures"));
        System.out.println();

        // ── Bob and Charlie answer ─────────────────────────────────────────────
        System.out.println("── Answers Posted ──");
        Answer a1 = so.postAnswer("U2", q1.id,
                "HashMap uses an array of buckets. Each bucket is a linked list...");
        Answer a2 = so.postAnswer("U3", q1.id,
                "Internally, HashMap uses hashCode() and equals() to store key-value pairs.");
        System.out.println();

        // ── Voting ────────────────────────────────────────────────────────────
        System.out.println("── Voting ──");
        so.voteQuestion("U2", q1.id, +1);  // Bob upvotes Alice's question
        so.voteQuestion("U3", q1.id, +1);  // Charlie upvotes Alice's question
        so.voteAnswer("U1", a1.id, +1);    // Alice upvotes Bob's answer
        so.voteAnswer("U3", a1.id, +1);    // Charlie upvotes Bob's answer
        so.voteAnswer("U1", a2.id, -1);    // Alice downvotes Charlie's answer
        System.out.println();

        // ── Vote retraction ───────────────────────────────────────────────────
        System.out.println("── Vote Retraction ──");
        so.voteAnswer("U3", a1.id, +1);    // Charlie votes again → retracts
        System.out.println();

        // ── Alice accepts Bob's answer ─────────────────────────────────────────
        System.out.println("── Accept Answer ──");
        so.acceptAnswer("U1", a1.id); // Alice (question owner) accepts Bob's answer
        System.out.println();

        // ── Tag search ────────────────────────────────────────────────────────
        System.out.println("── Search by Tag ──");
        so.postQuestion("U2", "Java generics explained?", "...",
                Arrays.asList("java", "generics"));
        List<Question> javaQs = so.searchByTag("java");
        javaQs.forEach(q -> System.out.println("  " + q));
        System.out.println();

        // ── Final state ───────────────────────────────────────────────────────
        System.out.println("── Final Question State ──");
        so.printQuestion(q1.id);

        System.out.println("\n── User Reputations ──");
        so.printUser("U1"); // Alice: +10+10 (2 upvotes received on Q) - 2 (downvote given — no rep)
        so.printUser("U2"); // Bob: +10+10 (2 upvotes on A) + 15 (accepted) = 35 (minus 1 retract = 25)
        so.printUser("U3"); // Charlie: +10 (1 upvote on Q from Alice) - 2 (downvote on A)
    }
}
