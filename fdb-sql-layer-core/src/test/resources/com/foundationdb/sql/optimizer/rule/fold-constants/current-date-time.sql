SELECT oid FROM ORDERS
 WHERE order_date > CURRENT_DATE - INTERVAL '7' DAY