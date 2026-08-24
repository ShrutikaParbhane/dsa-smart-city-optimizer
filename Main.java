import java.util.*;
import java.io.*;

// ============================== CLASS: AccountManager ==============================
class AccountManager {
    private static final String FILE_NAME = "accounts.txt";
    private static Map<String, String> accounts = new HashMap<>();
    private static Map<String, String> roles = new HashMap<>();

    private static String hashPassword(String password) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(password.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return password;
        }
    }

    static {
        boolean rewritten = false;
        List<String[]> loaded = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(FILE_NAME))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length == 3) {
                    String id = parts[0];
                    String pw = parts[1];
                    String role = parts[2];
                    
                    if (!(pw.length() == 64 && pw.matches("[0-9a-fA-F]+"))) {
                        pw = hashPassword(pw);
                        rewritten = true;
                    }
                    accounts.put(id, pw);
                    roles.put(id, role);
                    loaded.add(new String[]{id, pw, role});
                }
            }
        } catch (IOException e) {
            // File may not exist initially
        }
        
        if (rewritten && !loaded.isEmpty()) {
            try (BufferedWriter bw = new BufferedWriter(new FileWriter(FILE_NAME))) {
                for (String[] act : loaded) {
                    bw.write(act[0] + "," + act[1] + "," + act[2]);
                    bw.newLine();
                }
            } catch (IOException e) {
                System.out.println("Error upgrading accounts database.");
            }
        }
    }

    private static void saveAccount(String id, String password, String role) {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(FILE_NAME, true))) {
            bw.write(id + "," + password + "," + role);
            bw.newLine();
        } catch (IOException e) {
            System.out.println("Error saving account.");
        }
    }

    public static String signIn(Scanner sc) {
        System.out.print("Enter ID: ");
        String id = sc.next();
        System.out.print("Enter Password: ");
        String pw = sc.next();
        String hashed = hashPassword(pw);
        if (accounts.containsKey(id) && accounts.get(id).equals(hashed)) {
            System.out.println("Login successful.");
            return id;
        } else {
            System.out.println("Invalid credentials.");
            return null;
        }
    }

    public static String createAccount(Scanner sc) {
        System.out.print("Enter new ID: ");
        String id = sc.next();
        if (accounts.containsKey(id)) {
            System.out.println("ID already exists.");
            return null;
        }
        System.out.print("Enter new Password: ");
        String pw = sc.next();
        System.out.print("Enter role (municipal/citizen): ");
        String role = sc.next().toLowerCase();

        if (!role.equals("municipal") && !role.equals("citizen")) {
            System.out.println("Invalid role.");
            return null;
        }

        String hashed = hashPassword(pw);
        accounts.put(id, hashed);
        roles.put(id, role);
        saveAccount(id, hashed, role);
        System.out.println("Account created successfully.");
        return id;
    }

    public static String getRole(String id) {
        return roles.get(id);
    }
}

// ============================== CLASS: Resource ==============================
class Resource {
    String type, id, driverName;
    boolean available = true;

    Resource(String type, String id, String driverName) {
        this.type = type;
        this.id = id;
        this.driverName = driverName;
    }
}

// ============================== CLASS: Request ==============================
class Request {
    String requesterID, type, location, status, allocatedResource;
    int priority;
    int sequenceNum;

    Request(String requesterID, String type, String location, int priority, int sequenceNum) {
        this.requesterID = requesterID;
        this.type = type;
        this.location = location;
        this.priority = priority;
        this.status = "Pending";
        this.allocatedResource = "None";
        this.sequenceNum = sequenceNum;
    }
}

// ============================== CLASS: LRUCache ==============================
class LRUCache<K, V> extends LinkedHashMap<K, V> {
    private final int capacity;
    LRUCache(int capacity) {
        super(capacity, 0.75f, true);
        this.capacity = capacity;
    }
    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        return size() > capacity;
    }
}

// ============================== CLASS: CityGraph (WEIGHTED) ==============================
class CityGraph {
    // Modified: adjacency map now stores Map<neighbor, distance>
    Map<String, Map<String, Integer>> adj = new HashMap<>();
    Map<String, List<Resource>> resources = new HashMap<>();
    
    // LRU Cache for shortest routes
    LRUCache<String, List<String>> routeCache = new LRUCache<>(10);
    
    // Double-ended queue for priority emergency dispatches
    Deque<Request> requestQueue = new ArrayDeque<>();
    
    // Decoupled listener/callback triggered when a queued request is auto-assigned
    java.util.function.Consumer<Request> onQueueDispatchListener = null;

    void saveMapToFile(String filename) {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(filename))) {
            for (String area : adj.keySet()) {
                bw.write("AREA|" + area);
                bw.newLine();
            }
            Set<String> writtenRoads = new HashSet<>();
            for (String u : adj.keySet()) {
                for (Map.Entry<String, Integer> e : adj.get(u).entrySet()) {
                    String v = e.getKey();
                    String roadKey = u.compareTo(v) < 0 ? u + "-" + v : v + "-" + u;
                    if (!writtenRoads.contains(roadKey)) {
                        bw.write("ROAD|" + u + "|" + v + "|" + e.getValue());
                        bw.newLine();
                        writtenRoads.add(roadKey);
                    }
                }
            }
            for (String center : resources.keySet()) {
                bw.write("CENTER|" + center);
                bw.newLine();
                for (Resource r : resources.get(center)) {
                    bw.write("RESOURCE|" + center + "|" + r.type + "|" + r.id + "|" + r.driverName + "|" + r.available);
                    bw.newLine();
                }
            }
        } catch (IOException e) {
            System.out.println("Error saving map state to file.");
        }
    }

    void loadMapFromFile(String filename) {
        File file = new File(filename);
        if (!file.exists()) return;
        adj.clear();
        resources.clear();
        routeCache.clear();
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split("\\|", -1);
                if (parts.length < 2) continue;
                String tag = parts[0];
                if (tag.equals("AREA")) {
                    adj.putIfAbsent(parts[1], new HashMap<>());
                } else if (tag.equals("ROAD") && parts.length >= 4) {
                    String a = parts[1];
                    String b = parts[2];
                    int dist = Integer.parseInt(parts[3]);
                    adj.putIfAbsent(a, new HashMap<>());
                    adj.putIfAbsent(b, new HashMap<>());
                    adj.get(a).put(b, dist);
                    adj.get(b).put(a, dist);
                } else if (tag.equals("CENTER")) {
                    resources.putIfAbsent(parts[1], new ArrayList<>());
                } else if (tag.equals("RESOURCE") && parts.length >= 6) {
                    String center = parts[1];
                    String type = parts[2];
                    String id = parts[3];
                    String driver = parts[4];
                    boolean avail = Boolean.parseBoolean(parts[5]);
                    
                    Resource r = new Resource(type, id, driver);
                    r.available = avail;
                    resources.putIfAbsent(center, new ArrayList<>());
                    resources.get(center).add(r);
                }
            }
        } catch (IOException | NumberFormatException e) {
            System.out.println("Error loading map state from file.");
        }
    }

    void addArea(String area) {
        if (adj.containsKey(area)) {
            System.out.println("Area already exists!");
            return;
        }
        adj.put(area, new HashMap<>());
        routeCache.clear();
        saveMapToFile("map.txt");
    }

    // Modified: Add weighted road
    void addRoad(String a, String b, int distance) {
        if (distance <= 0) {
            System.out.println("Error: Road distance must be greater than zero!");
            return;
        }
        if (!adj.containsKey(a) || !adj.containsKey(b)) {
            System.out.println("One or both areas not found.");
            return;
        }
        if (adj.get(a).containsKey(b)) {
            System.out.println("Road already exists between these two areas!");
            return;
        }
        adj.get(a).put(b, distance);
        adj.get(b).put(a, distance);
        routeCache.clear();
        System.out.println("Road added between " + a + " and " + b + " with distance " + distance);
        saveMapToFile("map.txt");
    }

    void addResourceCenter(String area) {
        if (resources.containsKey(area)) {
            System.out.println("Resource center already exists at this area!");
            return;
        }
        resources.put(area, new ArrayList<>());
        System.out.println("Resource center added at " + area);
        saveMapToFile("map.txt");
    }

    void addResource(String area, Resource r) {
        if (!resources.containsKey(area)) {
            System.out.println("Resource center not found for area.");
            return;
        }
        // Duplicate ID check globally
        for (List<Resource> list : resources.values()) {
            for (Resource existing : list) {
                if (existing.id.equalsIgnoreCase(r.id)) {
                    System.out.println("Resource ID already exists globally!");
                    return;
                }
            }
        }
        resources.get(area).add(r);
        System.out.println("Resource added successfully at " + area);
        saveMapToFile("map.txt");
    }

    void displayMap() {
        System.out.println("\n--- City Map ---");
        for (String a : adj.keySet()) {
            System.out.print(a + " -> ");
            for (Map.Entry<String, Integer> e : adj.get(a).entrySet()) {
                System.out.print(e.getKey() + "(" + e.getValue() + " km) ");
            }
            System.out.println();
        }
    }

    void showAllResources() {
        System.out.println("\n--- All Resources ---");
        for (String area : resources.keySet()) {
            System.out.println(area + ":");
            for (Resource r : resources.get(area)) {
                System.out.println("   " + r.type + " | ID: " + r.id + " | Driver: " + r.driverName +
                        " | Available: " + r.available);
            }
        }
    }

    // Helper to calculate total path distance
    int getPathDistance(List<String> path) {
        if (path.isEmpty()) return Integer.MAX_VALUE;
        int totalDist = 0;
        for (int i = 0; i < path.size() - 1; i++) {
            String u = path.get(i);
            String v = path.get(i + 1);
            if (adj.containsKey(u) && adj.get(u).containsKey(v)) {
                totalDist += adj.get(u).get(v);
            } else {
                return Integer.MAX_VALUE;
            }
        }
        return totalDist;
    }

    // Modified: Dijkstra's algorithm for weighted shortest path with LRU caching
    List<String> shortestPath(String start, String end) {
        if (!adj.containsKey(start) || !adj.containsKey(end)) {
            return new ArrayList<>();
        }

        String cacheKey = start + "->" + end;
        if (routeCache.containsKey(cacheKey)) {
            System.out.println("[Cache Hit] Retrieved route from LRU cache: " + cacheKey);
            return new ArrayList<>(routeCache.get(cacheKey));
        }

        Map<String, Integer> dist = new HashMap<>();
        Map<String, String> parent = new HashMap<>();
        PriorityQueue<String> pq = new PriorityQueue<>(Comparator.comparingInt(dist::get));

        for (String node : adj.keySet()) {
            dist.put(node, Integer.MAX_VALUE);
        }

        dist.put(start, 0);
        pq.add(start);

        while (!pq.isEmpty()) {
            String node = pq.poll();

            for (Map.Entry<String, Integer> e : adj.get(node).entrySet()) {
                String nei = e.getKey();
                int w = e.getValue();
                if (dist.get(node) + w < dist.get(nei)) {
                    dist.put(nei, dist.get(node) + w);
                    parent.put(nei, node);
                    pq.add(nei);
                }
            }
        }

        if (dist.get(end) == Integer.MAX_VALUE)
            return new ArrayList<>();

        List<String> path = new ArrayList<>();
        for (String at = end; at != null; at = parent.get(at))
            path.add(at);
        Collections.reverse(path);

        routeCache.put(cacheKey, path);
        return path;
    }

    Resource allocateResource(Request req) {
        String emergencyArea = req.location;
        String type = req.type;
        
        Resource bestResource = null;
        String bestArea = null;
        int minDistance = Integer.MAX_VALUE;
        List<String> bestPath = null;

        for (String area : resources.keySet()) {
            for (Resource r : resources.get(area)) {
                if (r.type.equalsIgnoreCase(type) && r.available) {
                    List<String> path = shortestPath(area, emergencyArea);
                    int distance = getPathDistance(path);
                    if (distance < minDistance) {
                        minDistance = distance;
                        bestResource = r;
                        bestArea = area;
                        bestPath = path;
                    }
                }
            }
        }

        if (bestResource != null) {
            bestResource.available = false; // Safe lock: only lock after route selection confirmation
            System.out.println("\nAllocated " + bestResource.type + " (" + bestResource.id + ") from " + bestArea);
            System.out.println("Driver: " + bestResource.driverName);
            if (bestPath.isEmpty()) {
                System.out.println("No direct path found.");
            } else {
                System.out.println("Shortest path: " + bestPath);
            }
            return bestResource;
        }

        // If not allocated, add to queue
        req.status = "Queued";
        if (req.priority == 0) {
            requestQueue.addFirst(req);
        } else {
            requestQueue.addLast(req);
        }
        System.out.println("No available resource of type " + type + ". Request queued (Priority: " + req.priority + ").");
        return null;
    }

    void markComplete(int requestId) {
        Request targetReq = null;
        for (Request r : HistoryManager.getAllRequests()) {
            if (r.sequenceNum == requestId) {
                targetReq = r;
                break;
            }
        }

        if (targetReq == null || !"Assigned".equalsIgnoreCase(targetReq.status)) {
            System.out.println("No active assigned request found with ID: " + requestId);
            return;
        }

        Resource freedResource = null;
        String freedArea = null;
        for (String area : resources.keySet()) {
            for (Resource r : resources.get(area)) {
                if (r.id.equals(targetReq.allocatedResource)) {
                    r.available = true;
                    freedResource = r;
                    freedArea = area;
                    System.out.println("Task completed for resource " + r.id + " from request #" + requestId);
                    break;
                }
            }
            if (freedResource != null) break;
        }

        targetReq.status = "Completed";
        saveMapToFile("map.txt");

        // Priority + Nearest scheduling optimization for waiting requests in the queue
        List<Request> candidates = new ArrayList<>();
        for (Request req : requestQueue) {
            if (freedResource != null && req.type.equalsIgnoreCase(freedResource.type)) {
                // Ensure a valid path exists before considering this request for auto-dispatch
                List<String> path = shortestPath(freedArea, req.location);
                if (!path.isEmpty()) {
                    candidates.add(req);
                }
            }
        }

        if (!candidates.isEmpty() && freedResource != null) {
            final String sourceArea = freedArea;
            candidates.sort((r1, r2) -> {
                // 1. Priority (High priority first)
                if (r1.priority != r2.priority) {
                    return Integer.compare(r1.priority, r2.priority);
                }
                // 2. Nearest distance
                int dist1 = getPathDistance(shortestPath(sourceArea, r1.location));
                int dist2 = getPathDistance(shortestPath(sourceArea, r2.location));
                if (dist1 != dist2) {
                    return Integer.compare(dist1, dist2);
                }
                // 3. Sequence order
                return Integer.compare(r1.sequenceNum, r2.sequenceNum);
            });

            Request pending = candidates.get(0);
            freedResource.available = false;
            pending.allocatedResource = freedResource.id;
            pending.status = "Assigned";

            System.out.println("\n[Queue Dispatch] Automatically assigning freed resource " + freedResource.id + " to queued request from " + pending.requesterID);
            List<String> path = shortestPath(freedArea, pending.location);
            System.out.println("Driver: " + freedResource.driverName);
            if (path.isEmpty()) {
                System.out.println("No direct path found.");
            } else {
                System.out.println("Shortest path: " + path);
            }

            if (onQueueDispatchListener != null) {
                onQueueDispatchListener.accept(pending);
            }

            requestQueue.remove(pending);
            saveMapToFile("map.txt");
        }
    }
}

// ============================== CLASS: HistoryManager ==============================
class HistoryManager {
    private static Map<Integer, Request> allRequests = new TreeMap<>();
    private static int requestCounter = 1;

    static void addRequest(Request req) {
        allRequests.put(requestCounter++, req);
    }

    static void updateStatus(int requestId) {
        for (Request r : allRequests.values()) {
            if (r.sequenceNum == requestId) {
                r.status = "Completed";
            }
        }
    }

    static int getNextSequenceNum() {
        return requestCounter;
    }

    static Collection<Request> getAllRequests() {
        return allRequests.values();
    }

    static void loadHistoryFromFile(String filename) {
        allRequests.clear();
        requestCounter = 1;
        File file = new File(filename);
        if (!file.exists()) return;
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split("\\|", -1);
                if (parts.length >= 6) {
                    String reqId = parts[0];
                    String type = parts[1];
                    String loc = parts[2];
                    int priority = Integer.parseInt(parts[3]);
                    String status = parts[4];
                    String allocated = parts[5];
                    
                    Request req = new Request(reqId, type, loc, priority, requestCounter);
                    req.status = status;
                    req.allocatedResource = allocated;
                    allRequests.put(requestCounter++, req);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    static void showMunicipalHistory() {
        System.out.println("\n--- All Requests History ---");
        if (allRequests.isEmpty()) {
            System.out.println("No requests recorded yet.");
            return;
        }
        for (Map.Entry<Integer, Request> e : allRequests.entrySet()) {
            Request r = e.getValue();
            System.out.println("Request ID: " + e.getKey() + " | Type: " + r.type + " | Location: " + r.location
                    + " | Status: " + r.status + " | Resource: " + r.allocatedResource + " | Requested by: " + r.requesterID);
        }
    }

    static void showUserHistory(String userID) {
        System.out.println("\n--- Your Requests ---");
        boolean found = false;
        for (Map.Entry<Integer, Request> e : allRequests.entrySet()) {
            Request r = e.getValue();
            if (r.requesterID.equals(userID)) {
                System.out.println("Request ID: " + e.getKey() + " | Type: " + r.type + " | Location: " + r.location
                        + " | Status: " + r.status + " | Resource: " + r.allocatedResource);
                found = true;
            }
        }
        if (!found) System.out.println("You have no requests yet.");
    }
}

// ============================== MAIN CLASS ==============================
public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        CityGraph city = new CityGraph();

        // Load history and restore queued requests
        HistoryManager.loadHistoryFromFile("requests.txt");
        for (Request r : HistoryManager.getAllRequests()) {
            if ("Queued".equalsIgnoreCase(r.status)) {
                if (r.priority == 0) {
                    city.requestQueue.addFirst(r);
                } else {
                    city.requestQueue.addLast(r);
                }
            }
        }

        while (true) {
            System.out.println("\n===== SMART CITY EMERGENCY RESOURCE OPTIMIZER =====");
            System.out.println("1. Login");
            System.out.println("2. Sign Up");
            System.out.println("3. Exit");
            System.out.print("Enter choice: ");
            int opt = sc.nextInt();

            String userID = null;
            if (opt == 1) {
                userID = AccountManager.signIn(sc);
            } else if (opt == 2) {
                userID = AccountManager.createAccount(sc);
            } else if (opt == 3) {
                System.out.println("Exiting system. Goodbye!");
                return;
            } else {
                System.out.println("Invalid choice.");
                continue;
            }

            if (userID == null)
                continue;

            String role = AccountManager.getRole(userID);
            if (role.equals("municipal")) {
                while (true) {
                    System.out.println("\n--- MUNICIPAL DASHBOARD ---");
                    System.out.println("1. Add Area");
                    System.out.println("2. Add Road");
                    System.out.println("3. Display City Map");
                    System.out.println("4. Add Resource Center");
                    System.out.println("5. Add Resource");
                    System.out.println("6. Show All Resources");
                    System.out.println("7. Mark Task Complete");
                    System.out.println("8. View All History");
                    System.out.println("9. Logout");
                    System.out.print("Enter choice: ");
                    int ch = sc.nextInt();
                    switch (ch) {
                        case 1 -> {
                            System.out.print("Enter area: ");
                            city.addArea(sc.next());
                        }
                        case 2 -> {
                            System.out.print("Enter area1: ");
                            String a = sc.next();
                            System.out.print("Enter area2: ");
                            String b = sc.next();
                            System.out.print("Enter distance (km): ");
                            int d = sc.nextInt();
                            city.addRoad(a, b, d);
                        }
                        case 3 -> city.displayMap();
                        case 4 -> {
                            System.out.print("Enter center area: ");
                            city.addResourceCenter(sc.next());
                        }
                        case 5 -> {
                            System.out.print("Enter center: ");
                            String area = sc.next();
                            System.out.print("Enter type: ");
                            String type = sc.next();
                            System.out.print("Enter vehicle ID: ");
                            String id = sc.next();
                            System.out.print("Enter driver name: ");
                            String dn = sc.next();
                            city.addResource(area, new Resource(type, id, dn));
                        }
                        case 6 -> city.showAllResources();
                        case 7 -> {
                            System.out.print("Enter Request ID to complete: ");
                            int reqId = sc.nextInt();
                            city.markComplete(reqId);
                            HistoryManager.updateStatus(reqId);
                        }
                        case 8 -> HistoryManager.showMunicipalHistory();
                        case 9 -> {
                            System.out.println("Returning to Main Menu...");
                            break;
                        }
                        default -> System.out.println("Invalid choice");
                    }
                    if (ch == 9) break;
                }
            } else if (role.equals("citizen")) {
                while (true) {
                    System.out.println("\n--- CITIZEN DASHBOARD ---");
                    System.out.println("1. Show All Available Resources");
                    System.out.println("2. Create Emergency Request");
                    System.out.println("3. View My History");
                    System.out.println("4. Logout");
                    System.out.print("Enter choice: ");
                    int ch = sc.nextInt();
                    switch (ch) {
                        case 1 -> city.showAllResources();
                        case 2 -> {
                            System.out.print("Enter emergency area: ");
                            String ea = sc.next();
                            System.out.print("Enter resource type: ");
                            String type = sc.next();
                            System.out.print("Enter priority (0=High, 1=Medium, 2=Low): ");
                            int pri = sc.nextInt();
                            Request req = new Request(userID, type, ea, pri, HistoryManager.getNextSequenceNum());
                            Resource allocated = city.allocateResource(req);
                            if (allocated != null) {
                                req.allocatedResource = allocated.id;
                                req.status = "Assigned";
                            }
                            HistoryManager.addRequest(req);
                        }
                        case 3 -> HistoryManager.showUserHistory(userID);
                        case 4 -> {
                            System.out.println("Returning to Main Menu...");
                            break;
                        }
                        default -> System.out.println("Invalid choice");
                    }
                    if (ch == 4) break;
                }
            }
        }
    }
}