// =============================================================================
// LLD: FOOD DELIVERY REVIEWS SYSTEM (Meesho SDE3 Machine Coding — Feb 2026)
// =============================================================================
// REQUIREMENTS:
//   1. Add restaurants with cuisine type
//   2. Add menu items to restaurants (name, price)
//   3. Customers place orders (list of items from one restaurant)
//   4. Mark order as delivered
//   5. Customer reviews a delivered order — rates restaurant + individual items
//   6. Get average rating for a restaurant
//   7. Get all reviews for a specific menu item
//   8. Get top-rated restaurants by average rating
//
// CONSTRAINTS:
//   - Customer can only review an order ONCE (one review per order)
//   - Review only allowed after order is DELIVERED
//   - Rating must be 1–5
//
// KEY DESIGN DECISIONS:
//
// 1. Review targets both the restaurant AND individual items in one call.
//    ReviewService collects them as separate Review objects internally.
//
// 2. Average rating is computed on the fly from the reviews list.
//    In production you'd cache this (pre-aggregated counter) — for LLD,
//    on-demand computation is fine.
//
// 3. No concurrency needed here — the interviewer said "reviews system".
//    The only potential race is two reviews for same order — prevented by
//    a Set<orderId> tracking reviewed orders. If interviewer asks, mention:
//    "I'd add synchronized(order) around the reviewed-check + add to prevent
//     duplicate reviews from simultaneous requests."
// =============================================================================

import java.util.*;
import java.util.stream.Collectors;


// =============================================================================
// ENUMS
// =============================================================================

enum CuisineType  { NORTH_INDIAN, SOUTH_INDIAN, CHINESE, ITALIAN, FAST_FOOD }
enum OrderStatus  { PLACED, PREPARING, OUT_FOR_DELIVERY, DELIVERED, CANCELLED }


// =============================================================================
// ENTITIES
// =============================================================================

// ── Restaurant ────────────────────────────────────────────────────────────────
class Restaurant {
    public String       id;
    public String       name;
    public CuisineType  cuisine;
    public List<MenuItem> menu;

    public Restaurant(String id, String name, CuisineType cuisine) {
        this.id      = id;
        this.name    = name;
        this.cuisine = cuisine;
        this.menu    = new ArrayList<>();
    }

    @Override
    public String toString() {
        return String.format("Restaurant[%s | %s | %s | items=%d]",
                id, name, cuisine, menu.size());
    }
}

// ── MenuItem ──────────────────────────────────────────────────────────────────
class MenuItem {
    public String id;
    public String restaurantId;
    public String name;
    public double price;

    public MenuItem(String id, String restaurantId, String name, double price) {
        this.id           = id;
        this.restaurantId = restaurantId;
        this.name         = name;
        this.price        = price;
    }

    @Override
    public String toString() {
        return String.format("MenuItem[%s | %s | ₹%.0f]", id, name, price);
    }
}

// ── FoodOrder ─────────────────────────────────────────────────────────────────
class FoodOrder {
    public String         id;
    public String         customerId;
    public String         restaurantId;
    public List<String>   itemIds;        // ordered item IDs
    public double         totalAmount;
    public OrderStatus    status;
    public boolean        isReviewed;     // prevents duplicate review

    public FoodOrder(String id, String customerId, String restaurantId,
                     List<String> itemIds, double totalAmount) {
        this.id           = id;
        this.customerId   = customerId;
        this.restaurantId = restaurantId;
        this.itemIds      = itemIds;
        this.totalAmount  = totalAmount;
        this.status       = OrderStatus.PLACED;
        this.isReviewed   = false;
    }

    @Override
    public String toString() {
        return String.format("Order[%s | customer=%s | restaurant=%s | ₹%.0f | %s]",
                id, customerId, restaurantId, totalAmount, status);
    }
}

// ── Review ────────────────────────────────────────────────────────────────────
// One review object per target (restaurant gets one, each item gets one).
class Review {
    public String id;
    public String orderId;
    public String customerId;
    public String targetId;    // restaurantId or menuItemId
    public String targetType;  // "RESTAURANT" or "ITEM"
    public int    rating;      // 1–5
    public String comment;
    public long   createdAt;

    public Review(String id, String orderId, String customerId,
                  String targetId, String targetType, int rating, String comment) {
        this.id         = id;
        this.orderId    = orderId;
        this.customerId = customerId;
        this.targetId   = targetId;
        this.targetType = targetType;
        this.rating     = rating;
        this.comment    = comment;
        this.createdAt  = System.currentTimeMillis();
    }

    @Override
    public String toString() {
        return String.format("Review[%s | %s | ★%d | \"%s\"]",
                targetId, targetType, rating, comment);
    }
}

// ── Customer ──────────────────────────────────────────────────────────────────
class ReviewCustomer {
    public String        id;
    public String        name;
    public List<FoodOrder> orders;

    public ReviewCustomer(String id, String name) {
        this.id     = id;
        this.name   = name;
        this.orders = new ArrayList<>();
    }
}


// =============================================================================
// FOOD DELIVERY REVIEWS SERVICE
// =============================================================================

class FoodDeliveryReviewsService {
    private Map<String, Restaurant>   restaurants = new HashMap<>();
    private Map<String, MenuItem>     menuItems   = new HashMap<>();
    private Map<String, FoodOrder>    orders      = new HashMap<>();
    private Map<String, ReviewCustomer> customers = new HashMap<>();
    private Map<String, List<Review>> reviewsByTarget = new HashMap<>(); // targetId → reviews

    private int rCounter = 0, mCounter = 0, oCounter = 0,
                cCounter = 0, rvCounter = 0;

    // ── Setup ─────────────────────────────────────────────────────────────────

    public Restaurant addRestaurant(String name, CuisineType cuisine) {
        String id = "RST-" + (++rCounter);
        Restaurant r = new Restaurant(id, name, cuisine);
        restaurants.put(id, r);
        System.out.println("[SETUP] " + r);
        return r;
    }

    public MenuItem addMenuItem(String restaurantId, String name, double price) {
        Restaurant restaurant = restaurants.get(restaurantId);
        if (restaurant == null) { System.out.println("[SETUP] Restaurant not found"); return null; }

        String id = "ITEM-" + (++mCounter);
        MenuItem item = new MenuItem(id, restaurantId, name, price);
        restaurant.menu.add(item);
        menuItems.put(id, item);
        System.out.println("[SETUP] Added: " + item + " to " + restaurant.name);
        return item;
    }

    public ReviewCustomer registerCustomer(String name) {
        String id = "CUST-" + (++cCounter);
        ReviewCustomer c = new ReviewCustomer(id, name);
        customers.put(id, c);
        System.out.println("[SETUP] Customer: " + name + " (" + id + ")");
        return c;
    }

    // ── Order lifecycle ───────────────────────────────────────────────────────

    public FoodOrder placeOrder(String customerId, String restaurantId, List<String> itemIds) {
        ReviewCustomer customer    = customers.get(customerId);
        Restaurant     restaurant  = restaurants.get(restaurantId);
        if (customer == null || restaurant == null) {
            System.out.println("[ORDER] Customer or restaurant not found");
            return null;
        }

        // Validate all items belong to this restaurant and compute total
        double total = 0;
        for (String itemId : itemIds) {
            MenuItem item = menuItems.get(itemId);
            if (item == null || !item.restaurantId.equals(restaurantId)) {
                System.out.println("[ORDER] Item " + itemId + " not found in restaurant");
                return null;
            }
            total += item.price;
        }

        String orderId = "ORD-" + (++oCounter);
        FoodOrder order = new FoodOrder(orderId, customerId, restaurantId, itemIds, total);
        orders.put(orderId, order);
        customer.orders.add(order);
        System.out.println("[ORDER] Placed: " + order);
        return order;
    }

    public void deliverOrder(String orderId) {
        FoodOrder order = orders.get(orderId);
        if (order == null) { System.out.println("[ORDER] Not found: " + orderId); return; }
        if (order.status != OrderStatus.PLACED && order.status != OrderStatus.OUT_FOR_DELIVERY) {
            System.out.println("[ORDER] Cannot deliver: " + order.status);
            return;
        }
        order.status = OrderStatus.DELIVERED;
        System.out.println("[ORDER] Delivered: " + orderId);
    }

    // ── Review ────────────────────────────────────────────────────────────────
    // Customer reviews the restaurant and optionally each item in the order.
    // itemRatings: Map<menuItemId, rating> — can be partial (not every item rated)

    public void reviewOrder(String customerId, String orderId,
                            int restaurantRating, String restaurantComment,
                            Map<String, Integer> itemRatings,
                            Map<String, String>  itemComments) {
        FoodOrder order = orders.get(orderId);
        if (order == null) {
            System.out.println("[REVIEW] Order not found: " + orderId);
            return;
        }
        if (!order.customerId.equals(customerId)) {
            System.out.println("[REVIEW] You can only review your own orders");
            return;
        }
        if (order.status != OrderStatus.DELIVERED) {
            System.out.println("[REVIEW] Can only review DELIVERED orders, current: " + order.status);
            return;
        }
        if (order.isReviewed) {
            System.out.println("[REVIEW] Order already reviewed: " + orderId);
            return;
        }
        if (restaurantRating < 1 || restaurantRating > 5) {
            System.out.println("[REVIEW] Rating must be 1-5");
            return;
        }

        order.isReviewed = true;

        // 1. Review the restaurant
        String rvId = "RV-" + (++rvCounter);
        Review rstReview = new Review(rvId, orderId, customerId,
                order.restaurantId, "RESTAURANT", restaurantRating, restaurantComment);
        reviewsByTarget.computeIfAbsent(order.restaurantId, k -> new ArrayList<>()).add(rstReview);
        System.out.println("[REVIEW] Restaurant: " + rstReview);

        // 2. Review individual items (if provided)
        if (itemRatings != null) {
            for (Map.Entry<String, Integer> entry : itemRatings.entrySet()) {
                String itemId  = entry.getKey();
                int    iRating = entry.getValue();

                if (!order.itemIds.contains(itemId)) {
                    System.out.println("[REVIEW] Item " + itemId + " not in this order, skipping");
                    continue;
                }
                if (iRating < 1 || iRating > 5) continue;

                String itemComment = (itemComments != null) ? itemComments.getOrDefault(itemId, "") : "";
                String itemRvId    = "RV-" + (++rvCounter);
                Review itemReview  = new Review(itemRvId, orderId, customerId,
                        itemId, "ITEM", iRating, itemComment);
                reviewsByTarget.computeIfAbsent(itemId, k -> new ArrayList<>()).add(itemReview);
                System.out.println("[REVIEW] Item: " + itemReview);
            }
        }
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public double getRestaurantAvgRating(String restaurantId) {
        List<Review> reviews = reviewsByTarget.getOrDefault(restaurantId, new ArrayList<>());
        if (reviews.isEmpty()) {
            System.out.println("[RATING] No reviews yet for " + restaurantId);
            return 0;
        }
        double avg = reviews.stream().mapToInt(r -> r.rating).average().orElse(0);
        Restaurant rst = restaurants.get(restaurantId);
        System.out.printf("[RATING] %s → avg=%.2f (%d reviews)%n",
                rst != null ? rst.name : restaurantId, avg, reviews.size());
        return avg;
    }

    public List<Review> getItemReviews(String menuItemId) {
        MenuItem item = menuItems.get(menuItemId);
        List<Review> reviews = reviewsByTarget.getOrDefault(menuItemId, new ArrayList<>());
        System.out.println("[ITEM REVIEWS] " + (item != null ? item.name : menuItemId)
                + " — " + reviews.size() + " reviews:");
        reviews.forEach(r -> System.out.println("  ★" + r.rating + " — " + r.comment));
        return reviews;
    }

    // Returns restaurants sorted by average rating descending
    public List<Restaurant> getTopRatedRestaurants() {
        List<Restaurant> sorted = new ArrayList<>(restaurants.values());
        sorted.sort((a, b) -> {
            double avgA = reviewsByTarget.getOrDefault(a.id, new ArrayList<>())
                    .stream().mapToInt(r -> r.rating).average().orElse(0);
            double avgB = reviewsByTarget.getOrDefault(b.id, new ArrayList<>())
                    .stream().mapToInt(r -> r.rating).average().orElse(0);
            return Double.compare(avgB, avgA); // descending
        });
        System.out.println("[TOP] Restaurants by rating:");
        sorted.forEach(r -> {
            double avg = reviewsByTarget.getOrDefault(r.id, new ArrayList<>())
                    .stream().mapToInt(rv -> rv.rating).average().orElse(0);
            System.out.printf("  %s — ★%.2f%n", r.name, avg);
        });
        return sorted;
    }
}


// =============================================================================
// DEMO
// =============================================================================

public class FoodDeliveryReviewsSolution {
    public static void main(String[] args) {
        System.out.println("=== Food Delivery Reviews Demo ===\n");

        FoodDeliveryReviewsService service = new FoodDeliveryReviewsService();

        // ── Setup ──────────────────────────────────────────────────────────────
        System.out.println("── Setup ──");
        Restaurant bk  = service.addRestaurant("Burger King", CuisineType.FAST_FOOD);
        Restaurant mcd = service.addRestaurant("McDonald's", CuisineType.FAST_FOOD);
        Restaurant taj = service.addRestaurant("Taj Kitchen", CuisineType.NORTH_INDIAN);

        MenuItem whopper  = service.addMenuItem(bk.id,  "Whopper",     149.0);
        MenuItem fries    = service.addMenuItem(bk.id,  "Fries",        59.0);
        MenuItem bigmac   = service.addMenuItem(mcd.id, "Big Mac",     159.0);
        MenuItem mcFlurry = service.addMenuItem(mcd.id, "McFlurry",     89.0);
        MenuItem biryani  = service.addMenuItem(taj.id, "Biryani",     299.0);

        ReviewCustomer alice = service.registerCustomer("Alice");
        ReviewCustomer bob   = service.registerCustomer("Bob");
        System.out.println();

        // ── Orders ─────────────────────────────────────────────────────────────
        System.out.println("── Orders ──");
        FoodOrder o1 = service.placeOrder(alice.id, bk.id,
                Arrays.asList(whopper.id, fries.id));
        FoodOrder o2 = service.placeOrder(bob.id, mcd.id,
                Arrays.asList(bigmac.id, mcFlurry.id));
        FoodOrder o3 = service.placeOrder(alice.id, taj.id,
                Arrays.asList(biryani.id));
        System.out.println();

        // ── Deliver ────────────────────────────────────────────────────────────
        service.deliverOrder(o1.id);
        service.deliverOrder(o2.id);
        service.deliverOrder(o3.id);
        System.out.println();

        // ── Reviews ────────────────────────────────────────────────────────────
        System.out.println("── Alice Reviews Burger King Order ──");
        service.reviewOrder(alice.id, o1.id,
                4, "Good burgers, fast delivery",
                new HashMap<String, Integer>() {{ put(whopper.id, 5); put(fries.id, 3); }},
                new HashMap<String, String>()  {{ put(whopper.id, "Juicy and filling!"); put(fries.id, "A bit cold"); }}
        );
        System.out.println();

        System.out.println("── Bob Reviews McDonald's Order ──");
        service.reviewOrder(bob.id, o2.id,
                5, "Always consistent!",
                new HashMap<String, Integer>() {{ put(bigmac.id, 5); put(mcFlurry.id, 4); }},
                null
        );
        System.out.println();

        System.out.println("── Alice Reviews Taj Kitchen ──");
        service.reviewOrder(alice.id, o3.id,
                3, "Biryani was okay, not great",
                new HashMap<String, Integer>() {{ put(biryani.id, 3); }},
                new HashMap<String, String>()  {{ put(biryani.id, "Expected more spice"); }}
        );
        System.out.println();

        // ── Duplicate review attempt ───────────────────────────────────────────
        System.out.println("── Duplicate Review Attempt (should fail) ──");
        service.reviewOrder(alice.id, o1.id, 2, "Changed my mind", null, null);
        System.out.println();

        // ── Review before delivery (should fail) ──────────────────────────────
        System.out.println("── Review Before Delivery (should fail) ──");
        FoodOrder o4 = service.placeOrder(bob.id, bk.id, Arrays.asList(fries.id));
        service.reviewOrder(bob.id, o4.id, 5, "Premature review", null, null);
        System.out.println();

        // ── Queries ────────────────────────────────────────────────────────────
        System.out.println("── Restaurant Ratings ──");
        service.getRestaurantAvgRating(bk.id);
        service.getRestaurantAvgRating(mcd.id);
        service.getRestaurantAvgRating(taj.id);
        System.out.println();

        System.out.println("── Item Reviews ──");
        service.getItemReviews(whopper.id);
        System.out.println();

        System.out.println("── Top Rated Restaurants ──");
        service.getTopRatedRestaurants();
    }
}
