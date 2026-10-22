import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;

public class Store {
    private String name;
    private String dbUrl;

    public Store(String name) {
        this(name, "jdbc:sqlite:bookstore.db");
    }

    public Store(String name, String dbUrl) {
        this.name = name;
        this.dbUrl = dbUrl;
        initDatabase();
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(dbUrl);
    }

    private void initDatabase() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            // Users table (Admin, Vendor, Customer)
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS users ("
                    + "login_id TEXT PRIMARY KEY, "
                    + "name TEXT NOT NULL, "
                    + "password TEXT NOT NULL, "
                    + "role TEXT NOT NULL, "
                    + "phone TEXT, "
                    + "city TEXT"
                    + ")");

            // Books table
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS books ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "vendor_login TEXT NOT NULL, "
                    + "category TEXT NOT NULL, "
                    + "title TEXT NOT NULL, "
                    + "author TEXT NOT NULL, "
                    + "price REAL NOT NULL, "
                    + "quantity INTEGER NOT NULL, "
                    + "FOREIGN KEY(vendor_login) REFERENCES users(login_id)"
                    + ")");

            // Orders table
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS orders ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "customer_login TEXT NOT NULL, "
                    + "book_id INTEGER NOT NULL, "
                    + "quantity INTEGER NOT NULL, "
                    + "total REAL NOT NULL, "
                    + "FOREIGN KEY(customer_login) REFERENCES users(login_id), "
                    + "FOREIGN KEY(book_id) REFERENCES books(id)"
                    + ")");

            // Logs table
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS logs ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "entry TEXT NOT NULL"
                    + ")");

            // Book reservations and in-app customer notifications
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS reservations ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "book_id INTEGER NOT NULL, "
                    + "customer_login TEXT NOT NULL, "
                    + "status TEXT NOT NULL DEFAULT 'WAITING', "
                    + "created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "notified_at TEXT, "
                    + "FOREIGN KEY(book_id) REFERENCES books(id), "
                    + "FOREIGN KEY(customer_login) REFERENCES users(login_id)"
                    + ")");
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS notifications ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "customer_login TEXT NOT NULL, "
                    + "reservation_id INTEGER NOT NULL, "
                    + "message TEXT NOT NULL, "
                    + "is_read INTEGER NOT NULL DEFAULT 0, "
                    + "created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "FOREIGN KEY(customer_login) REFERENCES users(login_id), "
                    + "FOREIGN KEY(reservation_id) REFERENCES reservations(id)"
                    + ")");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_reservation_queue "
                    + "ON reservations(book_id, status, id)");

            // Seed default admin if missing
            try (PreparedStatement check = conn.prepareStatement("SELECT 1 FROM users WHERE LOWER(login_id) = 'admin'")) {
                ResultSet rs = check.executeQuery();
                if (!rs.next()) {
                    try (PreparedStatement insertAdmin = conn.prepareStatement(
                            "INSERT INTO users (login_id, name, password, role, phone, city) VALUES (?, ?, ?, ?, ?, ?)")) {
                        insertAdmin.setString(1, "admin");
                        insertAdmin.setString(2, "Administrator");
                        insertAdmin.setString(3, "admin123");
                        insertAdmin.setString(4, "Admin");
                        insertAdmin.setString(5, null);
                        insertAdmin.setString(6, null);
                        insertAdmin.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("Database initialization error: " + e.getMessage());
        }
    }

    public String getName() {
        return name;
    }

    // ---------- Login & Registration ----------

    public User login(String loginId, String password) throws StoreException {
        if (loginId == null || loginId.trim().isEmpty() || password == null || password.isEmpty()) {
            throw new StoreException("Enter your login ID and password.");
        }
        loginId = loginId.trim();

        String sql = "SELECT * FROM users WHERE LOWER(login_id) = LOWER(?) AND password = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, loginId);
            ps.setString(2, password);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return mapUser(rs);
            }
        } catch (SQLException e) {
            throw new StoreException("Database error: " + e.getMessage());
        }
        throw new StoreException("Invalid login ID or password.");
    }

    public void registerCustomer(String name, String email, String password) throws StoreException {
        if (name == null || name.trim().isEmpty() || email == null || email.trim().isEmpty() || password == null) {
            throw new StoreException("Name, email and password are required.");
        }
        email = email.trim().toLowerCase();
        if (!email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw new StoreException("Enter a valid email address.");
        }
        if (password.length() < 4) {
            throw new StoreException("Password must have at least 4 characters.");
        }
        if (loginIdTaken(email)) {
            throw new StoreException("This email is already registered.");
        }

        String sql = "INSERT INTO users (login_id, name, password, role) VALUES (?, ?, ?, 'Customer')";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, email);
            ps.setString(2, name.trim());
            ps.setString(3, password);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StoreException("Database error: " + e.getMessage());
        }
    }

    public void addVendor(String name, String loginId, String password, String phone, String city) throws StoreException {
        if (name == null || name.trim().isEmpty() || loginId == null || loginId.trim().isEmpty()
                || city == null || city.trim().isEmpty() || password == null) {
            throw new StoreException("Vendor name, login ID, password and city are required.");
        }
        if (password.length() < 4) {
            throw new StoreException("Password must have at least 4 characters.");
        }
        if (phone == null || !phone.trim().matches("\\d{7,15}")) {
            throw new StoreException("Phone must contain only digits (7 to 15).");
        }
        if (loginIdTaken(loginId.trim())) {
            throw new StoreException("This login ID is already used.");
        }

        String sql = "INSERT INTO users (login_id, name, password, role, phone, city) VALUES (?, ?, ?, 'Vendor', ?, ?)";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, loginId.trim());
            ps.setString(2, name.trim());
            ps.setString(3, password);
            ps.setString(4, phone.trim());
            ps.setString(5, city.trim());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StoreException("Database error: " + e.getMessage());
        }
    }

    // ---------- Books ----------

    public void addBook(Vendor vendor, String category, String title, String author, double price, int quantity) throws StoreException {
        if (title == null || title.trim().isEmpty() || author == null || author.trim().isEmpty()) {
            throw new StoreException("Title and author cannot be empty.");
        }
        if (price <= 0) {
            throw new StoreException("Price must be greater than 0.");
        }
        if (quantity < 0) {
            throw new StoreException("Quantity cannot be negative.");
        }
        if (category == null) {
            throw new StoreException("Please choose a category.");
        }

        title = title.trim();
        author = author.trim();

        String sql = "INSERT INTO books (vendor_login, category, title, author, price, quantity) VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, vendor.getLoginId());
            ps.setString(2, category);
            ps.setString(3, title);
            ps.setString(4, author);
            ps.setDouble(5, price);
            ps.setInt(6, quantity);
            ps.executeUpdate();
            addLog("Vendor " + vendor.getName() + " added book '" + title + "' (" + category + ") with stock " + quantity + ".");
        } catch (SQLException e) {
            throw new StoreException("Database error: " + e.getMessage());
        }
    }

    public void addStock(Vendor vendor, int bookId, int qty) throws StoreException {
        Book b = findBook(bookId);
        if (b == null || !b.getVendor().getLoginId().equalsIgnoreCase(vendor.getLoginId())) {
            throw new StoreException("You can only add stock to your own books.");
        }
        if (qty <= 0) {
            throw new StoreException("Stock quantity must be at least 1.");
        }

        String sql = "UPDATE books SET quantity = quantity + ? WHERE id = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, qty);
            ps.setInt(2, bookId);
            ps.executeUpdate();
            addLog("Vendor " + vendor.getName() + " added " + qty + " stock to '" + b.getTitle() + "'. Stock is now " + (b.getQuantity() + qty) + ".");
            notifyNextWaitingCustomer(bookId, b.getTitle());
        } catch (SQLException e) {
            throw new StoreException("Database error: " + e.getMessage());
        }
    }

    public void deleteBook(int id) throws StoreException {
        Book b = findBook(id);
        if (b == null) {
            throw new StoreException("Book not found.");
        }

        String sql = "DELETE FROM books WHERE id = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.executeUpdate();
            addLog("Admin deleted book '" + b.getTitle() + "' (vendor " + b.getVendor().getName() + ").");
        } catch (SQLException e) {
            throw new StoreException("Database error: " + e.getMessage());
        }
    }

    public ArrayList<Book> search(String keyword, String category) {
        ArrayList<Book> list = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT b.*, u.name as v_name, u.password as v_pass, u.phone as v_phone, u.city as v_city "
                + "FROM books b JOIN users u ON b.vendor_login = u.login_id WHERE 1=1 ");

        if (keyword != null && !keyword.trim().isEmpty()) {
            sql.append("AND (LOWER(b.title) LIKE ? OR LOWER(b.author) LIKE ?) ");
        }
        if (category != null && !category.trim().isEmpty()) {
            sql.append("AND b.category = ? ");
        }

        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int paramIndex = 1;
            if (keyword != null && !keyword.trim().isEmpty()) {
                String k = "%" + keyword.trim().toLowerCase() + "%";
                ps.setString(paramIndex++, k);
                ps.setString(paramIndex++, k);
            }
            if (category != null && !category.trim().isEmpty()) {
                ps.setString(paramIndex++, category.trim());
            }

            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                list.add(mapBook(rs));
            }
        } catch (SQLException e) {
            System.err.println("Search query error: " + e.getMessage());
        }
        return list;
    }

    public ArrayList<Book> booksOf(Vendor vendor) {
        ArrayList<Book> list = new ArrayList<>();
        String sql = "SELECT b.*, u.name as v_name, u.password as v_pass, u.phone as v_phone, u.city as v_city "
                   + "FROM books b JOIN users u ON b.vendor_login = u.login_id WHERE LOWER(b.vendor_login) = LOWER(?)";

        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, vendor.getLoginId());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                list.add(mapBook(rs));
            }
        } catch (SQLException e) {
            System.err.println("booksOf query error: " + e.getMessage());
        }
        return list;
    }

    // ---------- Book Reservations and Waiting List ----------

    public String reserveBook(Customer customer, int bookId) throws StoreException {
        Book book = findBook(bookId);
        if (book == null) throw new StoreException("Book not found.");
        if (book.getQuantity() > 0) {
            throw new StoreException("This book is currently available. You can place an order instead.");
        }

        String check = "SELECT 1 FROM reservations WHERE book_id = ? AND customer_login = ? "
                + "AND status IN ('WAITING', 'NOTIFIED')";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(check)) {
            ps.setInt(1, bookId);
            ps.setString(2, customer.getLoginId());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) throw new StoreException("You already have an active reservation for this book.");
            }
        } catch (SQLException e) {
            throw new StoreException("Could not check reservation: " + e.getMessage());
        }

        String insert = "INSERT INTO reservations (book_id, customer_login, status) VALUES (?, ?, 'WAITING')";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(insert)) {
            ps.setInt(1, bookId);
            ps.setString(2, customer.getLoginId());
            ps.executeUpdate();
            addLog("Customer " + customer.getName() + " joined the waiting list for '" + book.getTitle() + "'.");
            return "Reservation added. You are now on the waiting list for '" + book.getTitle() + "'.";
        } catch (SQLException e) {
            throw new StoreException("Could not save reservation: " + e.getMessage());
        }
    }

    // Called after a vendor adds stock. Notify the earliest waiting customer.
    private void notifyNextWaitingCustomer(int bookId, String bookTitle) {
        String nextSql = "SELECT id, customer_login FROM reservations "
                + "WHERE book_id = ? AND status = 'WAITING' ORDER BY id ASC LIMIT 1";
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                int reservationId;
                String customerLogin;
                try (PreparedStatement ps = conn.prepareStatement(nextSql)) {
                    ps.setInt(1, bookId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) { conn.rollback(); return; }
                        reservationId = rs.getInt("id");
                        customerLogin = rs.getString("customer_login");
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE reservations SET status = 'NOTIFIED', notified_at = CURRENT_TIMESTAMP "
                        + "WHERE id = ? AND status = 'WAITING'")) {
                    ps.setInt(1, reservationId);
                    if (ps.executeUpdate() != 1) { conn.rollback(); return; }
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO notifications (customer_login, reservation_id, message) VALUES (?, ?, ?)")) {
                    ps.setString(1, customerLogin);
                    ps.setInt(2, reservationId);
                    ps.setString(3, "'" + bookTitle + "' is now available. Please place your order.");
                    ps.executeUpdate();
                }
                conn.commit();
                addLog("Waiting-list notification sent for '" + bookTitle + "' to " + customerLogin + ".");
            } catch (SQLException e) {
                conn.rollback();
                System.err.println("Reservation notification error: " + e.getMessage());
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            System.err.println("Could not notify waiting customer: " + e.getMessage());
        }
    }

    public ArrayList<String[]> getReservationsForVendor(Vendor vendor) {
        ArrayList<String[]> list = new ArrayList<>();
        String sql = "SELECT r.id, b.title, u.name AS customer_name, r.customer_login, "
                + "r.status, r.created_at FROM reservations r "
                + "JOIN books b ON b.id = r.book_id "
                + "JOIN users u ON u.login_id = r.customer_login "
                + "WHERE b.vendor_login = ? AND r.status <> 'FULFILLED' ORDER BY r.id ASC";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, vendor.getLoginId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new String[]{rs.getString("id"), rs.getString("title"),
                            rs.getString("customer_name"), rs.getString("customer_login"),
                            rs.getString("status"), rs.getString("created_at")});
                }
            }
        } catch (SQLException e) {
            System.err.println("Vendor reservation list error: " + e.getMessage());
        }
        return list;
    }

    public ArrayList<String[]> getReservationsOf(Customer customer) {
        ArrayList<String[]> list = new ArrayList<>();
        String sql = "SELECT r.id, b.title, r.status, r.created_at FROM reservations r "
                + "JOIN books b ON b.id = r.book_id WHERE r.customer_login = ? ORDER BY r.id DESC";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, customer.getLoginId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(new String[]{rs.getString("id"), rs.getString("title"),
                        rs.getString("status"), rs.getString("created_at")});
            }
        } catch (SQLException e) { System.err.println("Reservation list error: " + e.getMessage()); }
        return list;
    }

    public ArrayList<String[]> getNotificationsOf(Customer customer) {
        ArrayList<String[]> list = new ArrayList<>();
        String sql = "SELECT id, message, created_at, is_read FROM notifications "
                + "WHERE customer_login = ? ORDER BY id DESC";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, customer.getLoginId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(new String[]{rs.getString("id"), rs.getString("message"),
                        rs.getString("created_at"), rs.getInt("is_read") == 1 ? "Read" : "Unread"});
            }
        } catch (SQLException e) { System.err.println("Notification list error: " + e.getMessage()); }
        return list;
    }

    public void markNotificationsRead(Customer customer) {
        String sql = "UPDATE notifications SET is_read = 1 WHERE customer_login = ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, customer.getLoginId());
            ps.executeUpdate();
        } catch (SQLException e) { System.err.println("Could not mark notifications read: " + e.getMessage()); }
    }

    // ---------- Orders ----------

    public String placeOrder(Customer customer, int bookId, int qty) throws StoreException {
        if (qty <= 0) {
            throw new StoreException("Order quantity must be at least 1.");
        }

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                Book b = findBook(conn, bookId);
                if (b == null) {
                    throw new StoreException("Book not available.");
                }
                if (qty > b.getQuantity()) {
                    throw new StoreException("Only " + b.getQuantity() + " copies of '" + b.getTitle() + "' in stock.");
                }

                // Reduce inventory
                try (PreparedStatement updateStock = conn.prepareStatement("UPDATE books SET quantity = quantity - ? WHERE id = ?")) {
                    updateStock.setInt(1, qty);
                    updateStock.setInt(2, bookId);
                    updateStock.executeUpdate();
                }

                // Insert order
                double total = b.getFinalPrice() * qty;
                try (PreparedStatement insertOrder = conn.prepareStatement(
                        "INSERT INTO orders (customer_login, book_id, quantity, total) VALUES (?, ?, ?, ?)")) {
                    insertOrder.setString(1, customer.getLoginId());
                    insertOrder.setInt(2, bookId);
                    insertOrder.setInt(3, qty);
                    insertOrder.setDouble(4, total);
                    insertOrder.executeUpdate();
                }

                try (PreparedStatement completeReservation = conn.prepareStatement(
                        "UPDATE reservations SET status = 'FULFILLED' WHERE book_id = ? "
                        + "AND customer_login = ? AND status = 'NOTIFIED'")) {
                    completeReservation.setInt(1, bookId);
                    completeReservation.setString(2, customer.getLoginId());
                    completeReservation.executeUpdate();
                }
                conn.commit();
                return "Order placed: " + qty + " x " + b.getTitle() + ". Total: Rs." + String.format("%.2f", total);
            } catch (Exception ex) {
                conn.rollback();
                if (ex instanceof StoreException) throw (StoreException) ex;
                throw new StoreException("Failed to process order: " + ex.getMessage());
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new StoreException("Database error: " + e.getMessage());
        }
    }

    public ArrayList<Order> getOrders() {
        return fetchOrders(null);
    }

    public ArrayList<Order> getOrdersOf(Customer customer) {
        return fetchOrders(customer.getLoginId());
    }

    private ArrayList<Order> fetchOrders(String customerLogin) {
        ArrayList<Order> list = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT o.id as o_id, o.quantity as o_qty, o.total as o_total, "
                + "c.name as c_name, c.login_id as c_email, c.password as c_pass, "
                + "b.id as b_id, b.title as b_title, b.author as b_author, b.price as b_price, b.quantity as b_qty, b.category as b_cat, "
                + "v.name as v_name, v.login_id as v_login, v.password as v_pass, v.phone as v_phone, v.city as v_city "
                + "FROM orders o "
                + "JOIN users c ON o.customer_login = c.login_id "
                + "JOIN books b ON o.book_id = b.id "
                + "JOIN users v ON b.vendor_login = v.login_id ");

        if (customerLogin != null) {
            sql.append("WHERE LOWER(o.customer_login) = LOWER(?) ");
        }
        sql.append("ORDER BY o.id DESC");

        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            if (customerLogin != null) {
                ps.setString(1, customerLogin);
            }
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Customer cust = new Customer(rs.getString("c_name"), rs.getString("c_email"), rs.getString("c_pass"));
                Vendor vend = new Vendor(rs.getString("v_name"), rs.getString("v_login"), rs.getString("v_pass"), rs.getString("v_phone"), rs.getString("v_city"));
                Book bk = instantiateBook(rs.getString("b_cat"), rs.getInt("b_id"), rs.getString("b_title"), rs.getString("b_author"), rs.getDouble("b_price"), rs.getInt("b_qty"), vend);
                list.add(new Order(rs.getInt("o_id"), cust, bk, rs.getInt("o_qty"), rs.getDouble("o_total")));
            }
        } catch (SQLException e) {
            System.err.println("fetchOrders error: " + e.getMessage());
        }
        return list;
    }

    // ---------- Users, Vendors & Customers Accessors ----------

    public ArrayList<Vendor> getVendors() {
        ArrayList<Vendor> list = new ArrayList<>();
        String sql = "SELECT * FROM users WHERE role = 'Vendor'";
        try (Connection conn = getConnection(); Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                list.add((Vendor) mapUser(rs));
            }
        } catch (SQLException e) {
            System.err.println("getVendors error: " + e.getMessage());
        }
        return list;
    }

    public ArrayList<Customer> getCustomers() {
        ArrayList<Customer> list = new ArrayList<>();
        String sql = "SELECT * FROM users WHERE role = 'Customer'";
        try (Connection conn = getConnection(); Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                list.add((Customer) mapUser(rs));
            }
        } catch (SQLException e) {
            System.err.println("getCustomers error: " + e.getMessage());
        }
        return list;
    }

    public ArrayList<String> getLog() {
        ArrayList<String> list = new ArrayList<>();
        String sql = "SELECT entry FROM logs ORDER BY id DESC";
        try (Connection conn = getConnection(); Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                list.add(rs.getString("entry"));
            }
        } catch (SQLException e) {
            System.err.println("getLog error: " + e.getMessage());
        }
        return list;
    }

    // ---------- Helpers ----------

    private void addLog(String text) {
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm"));
        String entry = time + "  -  " + text;
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement("INSERT INTO logs (entry) VALUES (?)")) {
            ps.setString(1, entry);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Failed to write log: " + e.getMessage());
        }
    }

    private Book findBook(int id) {
        try (Connection conn = getConnection()) {
            return findBook(conn, id);
        } catch (SQLException e) {
            return null;
        }
    }

    private Book findBook(Connection conn, int id) throws SQLException {
        String sql = "SELECT b.*, u.name as v_name, u.password as v_pass, u.phone as v_phone, u.city as v_city "
                   + "FROM books b JOIN users u ON b.vendor_login = u.login_id WHERE b.id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return mapBook(rs);
            }
        }
        return null;
    }

    private boolean loginIdTaken(String loginId) {
        String sql = "SELECT 1 FROM users WHERE LOWER(login_id) = LOWER(?)";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, loginId);
            ResultSet rs = ps.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            return true;
        }
    }

    private User mapUser(ResultSet rs) throws SQLException {
        String role = rs.getString("role");
        if ("Admin".equalsIgnoreCase(role)) {
            return new Admin(rs.getString("name"), rs.getString("login_id"), rs.getString("password"));
        } else if ("Vendor".equalsIgnoreCase(role)) {
            return new Vendor(rs.getString("name"), rs.getString("login_id"), rs.getString("password"),
                    rs.getString("phone"), rs.getString("city"));
        } else {
            return new Customer(rs.getString("name"), rs.getString("login_id"), rs.getString("password"));
        }
    }

    private Book mapBook(ResultSet rs) throws SQLException {
        Vendor v = new Vendor(rs.getString("v_name"), rs.getString("vendor_login"),
                rs.getString("v_pass"), rs.getString("v_phone"), rs.getString("v_city"));
        return instantiateBook(
                rs.getString("category"),
                rs.getInt("id"),
                rs.getString("title"),
                rs.getString("author"),
                rs.getDouble("price"),
                rs.getInt("quantity"),
                v
        );
    }

    private Book instantiateBook(String category, int id, String title, String author, double price, int qty, Vendor v) {
        switch (category) {
            case "Fiction": return new Fiction(id, title, author, price, qty, v);
            case "Crime":   return new Crime(id, title, author, price, qty, v);
            case "Horror":  return new Horror(id, title, author, price, qty, v);
            case "Comedy":  return new Comedy(id, title, author, price, qty, v);
            default:        return new Fiction(id, title, author, price, qty, v);
        }
    }
}
