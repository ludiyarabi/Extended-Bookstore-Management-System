# Code Shelf - Book Store Management System (Java Swing + SQLite)

## New feature: Book Reservation and Waiting List

Customers can select a book with zero stock and click **Reserve if Unavailable**. Reservations are saved in SQLite in FIFO order. When a vendor adds stock, the system changes the first waiting reservation to `NOTIFIED` and saves an in-app notification. Customers can view their reservation status and notifications in the customer screen tabs. After a notified customer places an order for that book, their reservation is marked `FULFILLED`.

### Files changed
- `Store.java`: creates `reservations` and `notifications` tables; implements reservation, queue notification, and notification-list operations; triggers notification when stock is added.
- `BookstoreSwingApp.java`: adds the **Reserve if Unavailable** button and **My Reservations** / **Notifications** tabs.

The new tables are created automatically when the application starts. Existing books, customers, vendors, and orders are not deleted.

### Run on Windows (VS Code terminal)
From the project folder:

```bash
javac -cp ".;lib/*" *.java
java -cp ".;lib/*" BookstoreSwingApp
```

Keep `lib/sqlite-jdbc-3.45.1.0.jar` in the `lib` folder. The project is a Java Swing desktop application (not a web application).

### Test the feature
1. Log in as a vendor and add a book with quantity `0` (or select an existing book after its stock reaches zero).
2. Register/log in as a customer, select the out-of-stock book, and click **Reserve if Unavailable**.
3. Log in as the vendor who owns that book and add stock.
4. Log back in as the customer and open **Notifications**. The first waiting customer should have a notification; **My Reservations** shows `NOTIFIED`.
5. Place an order for that book to mark the reservation `FULFILLED`.

Note: the notification is in-app, not email/SMS. The current implementation notifies one next waiting customer when stock is added; it does not physically hold a copy, so another customer may order the available stock first.
