import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executors;

public class NbuApp {

    public static void main(String[] args) throws Exception {
        String mode = (args.length > 0) ? args[0].toLowerCase() : "menu";

        switch (mode) {
            case "server" -> new Server().start();
            case "client" -> new Client().run();
            default -> {
                System.out.println("Оберіть режим:");
                System.out.println("  [1] Сервер");
                System.out.println("  [2] Клієнт");
                System.out.print("Ваш вибір: ");
                String choice = new Scanner(System.in).nextLine().trim();
                if ("1".equals(choice))      new Server().start();
                else if ("2".equals(choice)) new Client().run();
                else System.out.println("Невірний вибір. Запустіть: java NbuApp server|client");
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  МОДЕЛЬ
    // ══════════════════════════════════════════════════════════════════

    /**
     *JSON від API НБУ.
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    static class Currency {

        /** Цифровий код ISO 4217 (напр. 840 = USD) */
        @JsonProperty("r030") private int    numericCode;

        /** Назва валюти (укр.) */
        @JsonProperty("txt")  private String name;

        /** Офіційний курс НБУ до гривні */
        @JsonProperty("rate") private double rate;

        /** Літерний код ISO 4217 (напр. "USD") */
        @JsonProperty("cc")   private String code;

        /** Дата встановлення курсу (dd.MM.yyyy) */
        @JsonProperty("exchangedate") private String exchangeDate;

        // -- конструктори --
        public Currency() {}
        public Currency(int numericCode, String name, double rate, String code, String exchangeDate) {
            this.numericCode = numericCode; this.name = name; this.rate = rate;
            this.code = code;              this.exchangeDate = exchangeDate;
        }

        // -- бізнес-методи --
        /** Конвертація суми з валюти у гривні */
        public double convertToUah(double amount) { return amount * rate; }

        /** Конвертація суми з гривень у валюту */
        public double convertFromUah(double uah) {
            if (rate == 0) throw new ArithmeticException("Курс не може бути 0");
            return uah / rate;
        }

        /** Рядок вигляду "1 USD = 41.8500 UAH" */
        public String getFormattedRate() {
            return String.format("1 %s = %.4f UAH", code, rate);
        }

        // -- getters/setters --
        public int    getNumericCode()  { return numericCode; }
        public String getName()         { return name; }
        public double getRate()         { return rate; }
        public String getCode()         { return code; }
        public String getExchangeDate() { return exchangeDate; }

        public void setNumericCode(int v)    { this.numericCode = v; }
        public void setName(String v)        { this.name = v; }
        public void setRate(double v)        { this.rate = v; }
        public void setCode(String v)        { this.code = v; }
        public void setExchangeDate(String v){ this.exchangeDate = v; }

        @Override
        public String toString() {
            return String.format("Currency{code='%s', name='%s', rate=%.4f, date='%s'}",
                    code, name, rate, exchangeDate);
        }
    }

    static class ExchangeRateResponse {

        private List<Currency> currencies;
        private LocalDateTime  fetchedAt;
        private final String   source = "НБУ (bank.gov.ua)";

        public ExchangeRateResponse() { fetchedAt = LocalDateTime.now(); }
        public ExchangeRateResponse(List<Currency> c) { this(); currencies = c; }

        /** Пошук за літерним кодом (регістр не важливий) */
        public Optional<Currency> findByCode(String code) {
            if (code == null || currencies == null) return Optional.empty();
            return currencies.stream()
                    .filter(c -> code.equalsIgnoreCase(c.getCode()))
                    .findFirst();
        }

        /** Пошук за цифровим кодом ISO 4217 */
        public Optional<Currency> findByNumericCode(int code) {
            if (currencies == null) return Optional.empty();
            return currencies.stream()
                    .filter(c -> c.getNumericCode() == code)
                    .findFirst();
        }

        /** Топ-N валют із найвищим курсом */
        public List<Currency> getTopByRate(int n) {
            if (currencies == null) return Collections.emptyList();
            return currencies.stream()
                    .sorted(Comparator.comparingDouble(Currency::getRate).reversed())
                    .limit(n).toList();
        }

        public int    getCurrencyCount() { return currencies == null ? 0 : currencies.size(); }
        public String getRateDate()      { return (currencies == null || currencies.isEmpty())
                ? "невідомо" : currencies.getFirst().getExchangeDate(); }

        public List<Currency> getCurrencies()    { return currencies; }
        public void setCurrencies(List<Currency> c) { this.currencies = c; }
        public LocalDateTime  getFetchedAt()     { return fetchedAt; }
        public String         getSource()        { return source; }

        @Override
        public String toString() {
            return String.format("ExchangeRateResponse{source='%s', date='%s', count=%d}",
                    source, getRateDate(), getCurrencyCount());
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  СЕРВЕР
    // ══════════════════════════════════════════════════════════════════

    /**
     * HTTP-сервер (порт 8080). Ендпоінти:
     *   GET /api/rates          — всі курси
     *   GET /api/rates?code=USD — курс однієї валюти
     *   GET /api/rates/top?n=5  — топ-N за курсом
     *   GET /health             — стан сервера
     */
    static class Server {

        static final int    PORT        = 8080;
        static final String NBU_API_URL =
                "https://bank.gov.ua/NBUStatService/v1/statdirectory/exchange?json";
        static final int    CACHE_TTL   = 10; // хвилин

        private volatile ExchangeRateResponse cache;
        private volatile LocalDateTime        cacheTime;

        private final ObjectMapper mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);
        private final HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build();

        public void start() throws Exception {
            HttpServer srv = HttpServer.create(new InetSocketAddress(PORT), 0);
            srv.createContext("/api/rates/top", ex -> { try { handle(ex, "top");   } catch (Exception e) { throw new RuntimeException(e); } });
            srv.createContext("/api/rates",     ex -> { try { handle(ex, "rates"); } catch (Exception e) { throw new RuntimeException(e); } });
            srv.createContext("/health",        ex -> { try { handle(ex, "health");} catch (Exception e) { throw new RuntimeException(e); } });
            srv.setExecutor(Executors.newFixedThreadPool(4));
            srv.start();

            System.out.println("╔══════════════════════════════════════╗");
            System.out.println("║   НБУ Currency Server запущено!      ║");
            System.out.printf( "║   Порт: %-29d║%n", PORT);
            System.out.println("║   /api/rates   — всі курси           ║");
            System.out.println("║   /api/rates?code=USD — один курс    ║");
            System.out.println("║   /api/rates/top?n=5  — топ-N        ║");
            System.out.println("║   /health      — стан сервера        ║");
            System.out.println("╚══════════════════════════════════════╝");
        }

        private void handle(HttpExchange ex, String type) throws Exception {
            if (!"GET".equals(ex.getRequestMethod())) {
                sendJson(ex, 405, "{\"error\":\"Дозволено тільки GET\"}");
                return;
            }
            try {
                String query = ex.getRequestURI().getQuery();
                switch (type) {
                    case "rates" -> {
                        ExchangeRateResponse data = getRates();
                        if (query != null && query.startsWith("code=")) {
                            String code = query.substring(5).toUpperCase();
                            var found = data.findByCode(code);
                            if (found.isPresent()) sendJson(ex, 200, found.get());
                            else sendJson(ex, 404, "{\"error\":\"Валюту не знайдено\"}");
                        } else {
                            sendJson(ex, 200, data.getCurrencies());
                        }
                    }
                    case "top" -> {
                        int n = 10;
                        if (query != null && query.startsWith("n="))
                            try { n = Integer.parseInt(query.substring(2)); } catch (Exception ignored) {}
                        sendJson(ex, 200, getRates().getTopByRate(n));
                    }
                    case "health" -> {
                        String body = String.format(
                                "{\"status\":\"UP\",\"port\":%d,\"cache\":\"%s\",\"time\":\"%s\"}",
                                PORT,
                                isCacheValid() ? "VALID" : "EMPTY",
                                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                        sendJson(ex, 200, body);
                    }
                }
            } catch (Exception e) {
                sendJson(ex, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }

        private synchronized ExchangeRateResponse getRates() throws Exception {
            if (isCacheValid()) {
                System.out.println("[Server] Відповідь із кешу");
                return cache;
            }
            System.out.println("[Server] Завантаження з НБУ...");
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(NBU_API_URL))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200)
                throw new RuntimeException("НБУ відповів: " + resp.statusCode());
            List<Currency> list = mapper.readValue(resp.body(),
                    mapper.getTypeFactory().constructCollectionType(List.class, Currency.class));
            cache     = new ExchangeRateResponse(list);
            cacheTime = LocalDateTime.now();
            System.out.printf("[Server] Отримано %d валют. Дата: %s%n",
                    cache.getCurrencyCount(), cache.getRateDate());
            return cache;
        }

        private boolean isCacheValid() {
            return cache != null && cacheTime != null
                    && LocalDateTime.now().isBefore(cacheTime.plusMinutes(CACHE_TTL));
        }

        private void sendJson(HttpExchange ex, int status, Object body) throws Exception {
            byte[] data = (body instanceof String s)
                    ? s.getBytes() : mapper.writeValueAsBytes(body);
            ex.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
            ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            ex.sendResponseHeaders(status, data.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(data); }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  КЛІЄНТ
    // ══════════════════════════════════════════════════════════════════

    /**
     * Консольний клієнт із інтерактивним меню.
     */
    static class Client {

        private static final String BASE = "http://localhost:" + Server.PORT;
        private final HttpClient   http  = HttpClient.newHttpClient();
        private final ObjectMapper mapper = new ObjectMapper();
        private final Scanner      sc     = new Scanner(System.in);

        public void run() {
            System.out.println("╔══════════════════════════════════════╗");
            System.out.println("║  💰  НБУ Курс Валют — Клієнт        ║");
            System.out.println("╚══════════════════════════════════════╝");

            boolean running = true;
            while (running) {
                System.out.println("\n┌──────────────────────────────┐");
                System.out.println("│  [1] Всі курси валют         │");
                System.out.println("│  [2] Курс за кодом           │");
                System.out.println("│  [3] Топ-N валют             │");
                System.out.println("│  [4] Конвертер               │");
                System.out.println("│  [5] Стан сервера            │");
                System.out.println("│  [0] Вихід                   │");
                System.out.println("└──────────────────────────────┘");
                System.out.print("  Ваш вибір: ");

                switch (sc.nextLine().trim()) {
                    case "1" -> allRates();
                    case "2" -> rateByCode();
                    case "3" -> topRates();
                    case "4" -> convert();
                    case "5" -> health();
                    case "0" -> { System.out.println("👋 До побачення!"); running = false; }
                    default  -> System.out.println("⚠️  Невірний вибір.");
                }
            }
        }

        private void allRates() {
            try {
                List<Currency> list = mapper.readValue(get("/api/rates"),
                        new TypeReference<>() {});
                System.out.printf("%n%-8s %-30s %-12s %-12s%n", "Код","Назва","Курс(UAH)","Дата");
                System.out.println("─".repeat(66));
                for (Currency c : list)
                    System.out.printf("%-8s %-30s %,-12.4f %-12s%n",
                            c.getCode(), trunc(c.getName(), 30),
                            c.getRate(), c.getExchangeDate());
                System.out.printf("%nВсього: %d валют%n", list.size());
            } catch (Exception e) { err(e); }
        }

        private void rateByCode() {
            System.out.print("\n🔍 Код валюти (напр. USD): ");
            String code = sc.nextLine().trim().toUpperCase();
            try {
                String json = get("/api/rates?code=" + code);
                if (json.contains("\"error\"")) { System.out.println("  ❌ Не знайдено: " + code); return; }
                Currency c = mapper.readValue(json, Currency.class);
                System.out.println("\n┌─────────────────────────────────────┐");
                System.out.printf( "│  %-35s│%n", c.getName());
                System.out.println("├─────────────────────────────────────┤");
                System.out.printf( "│  Код:      %-25s│%n", c.getCode());
                System.out.printf( "│  ISO-код:  %-25d│%n", c.getNumericCode());
                System.out.printf( "│  Курс:     %-25.4f│%n", c.getRate());
                System.out.printf( "│  Дата:     %-25s│%n", c.getExchangeDate());
                System.out.println("└─────────────────────────────────────┘");
            } catch (Exception e) { err(e); }
        }

        private void topRates() {
            System.out.print("\n🏆 Скільки валют? (за замовчуванням 10): ");
            String in = sc.nextLine().trim();
            int n = 10;
            try { if (!in.isBlank()) n = Integer.parseInt(in); } catch (Exception ignored) {}
            try {
                List<Currency> top = mapper.readValue(get("/api/rates/top?n=" + n),
                        new TypeReference<>() {});
                System.out.printf("%n  Топ-%d валют:%n%n", top.size());
                System.out.printf("  %-4s %-8s %-30s %-12s%n", "№","Код","Назва","Курс(UAH)");
                System.out.println("  " + "─".repeat(56));
                for (int i = 0; i < top.size(); i++) {
                    Currency c = top.get(i);
                    System.out.printf("  %-4d %-8s %-30s %,.4f%n",
                            i+1, c.getCode(), trunc(c.getName(), 30), c.getRate());
                }
            } catch (Exception e) { err(e); }
        }

        private void convert() {
            System.out.print("\n💱 Код валюти (напр. USD): ");
            String code = sc.nextLine().trim().toUpperCase();
            try {
                String json = get("/api/rates?code=" + code);
                if (json.contains("\"error\"")) { System.out.println("  ❌ Не знайдено: " + code); return; }
                Currency c = mapper.readValue(json, Currency.class);
                System.out.println("  " + c.getFormattedRate());
                System.out.println("  [1] " + code + " → UAH   [2] UAH → " + code);
                System.out.print("  Вибір: ");
                String dir = sc.nextLine().trim();
                System.out.print("  Сума: ");
                double amount = Double.parseDouble(sc.nextLine().trim().replace(",", "."));
                if ("1".equals(dir))
                    System.out.printf("%n  %.2f %s = %.2f UAH%n", amount, code, c.convertToUah(amount));
                else if ("2".equals(dir))
                    System.out.printf("%n  %.2f UAH = %.4f %s%n", amount, c.convertFromUah(amount), code);
            } catch (Exception e) { err(e); }
        }

        private void health() {
            try { System.out.println("\n" + get("/health")); }
            catch (Exception e) { err(e); }
        }

        private String get(String path) throws Exception {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BASE + path))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .GET().build();
            return http.send(req, HttpResponse.BodyHandlers.ofString()).body();
        }

        private void err(Exception e) {
            System.out.println("  ❌ Помилка: " + e.getMessage());
            System.out.println("  Переконайтесь, що сервер запущено (порт " + Server.PORT + ").");
        }

        private String trunc(String s, int max) {
            if (s == null) return "";
            return s.length() <= max ? s : s.substring(0, max - 1) + "…";
        }
    }
}
