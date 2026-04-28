package org.example;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.*;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

public class websiteTesting {

    static WebDriver driver;
    static Actions actions;
    static JavascriptExecutor jsExecutor;
    static WebDriverWait wait;
    static Set<String> testedUrls;
    static List<TestResult> testResults;
    static String screenshotDir;
    static Sheets sheetsService;
    static final String SPREADSHEET_ID = "1HQAUwmh0qW0g_MKi5TidC8O5piK89-QlwPzkPphPqts";
    static String mainSheetName;
    static boolean isFirstWebsite = true;

    // Thread pool for parallel URL checking
    static ExecutorService executorService;
    static final int MAX_PARALLEL_CHECKS = 10;

    // Connection timeout settings
    static final int CONNECTION_TIMEOUT = 5000;
    static final int READ_TIMEOUT = 5000;

    // Cache for already checked URLs
    static Map<String, CachedUrlStatus> urlCache = new ConcurrentHashMap<>();

    // Store all discovered URLs from the website
    static Set<String> allDiscoveredUrls = new ConcurrentSkipListSet<>();
    static String baseDomain;

    // Patterns to skip
    static final Pattern SOCIAL_MEDIA_PATTERN = Pattern.compile(
            "(facebook|twitter|x\\.com|linkedin|instagram|youtube|youtu\\.be|pinterest|tiktok|snapchat|whatsapp|telegram|discord|reddit|tumblr|flickr|vimeo|dribbble|behance|medium|quora|twitch|periscope|vine|myspace|meetup|slack|wechat|line)"
    );

    static final Pattern FILE_EXTENSION_PATTERN = Pattern.compile(
            "\\.(pdf|doc|docx|xls|xlsx|ppt|pptx|zip|rar|7z|tar|gz|jpg|jpeg|png|gif|bmp|svg|mp3|mp4|avi|mov|wmv|flv|csv|txt|xml|json|exe|msi|dmg|iso)$",
            Pattern.CASE_INSENSITIVE
    );

    static class CachedUrlStatus {
        final int statusCode;
        final long timestamp;

        CachedUrlStatus(int statusCode) {
            this.statusCode = statusCode;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isValid() {
            return System.currentTimeMillis() - timestamp < 60000;
        }
    }

    private static final String APPLICATION_NAME = "Website Testing Automation";
    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();

    static class TestResult {
        String dateTime;
        String websiteUrl;
        String pageTitle;
        String parentPage;
        String childPage;
        String fullPath;
        String action;
        String testedUrl;
        Long durationMs;
        String failedResponseLog;
        Integer statusCode;
        String status;
        String screenshotPath;

        public TestResult(String dateTime, String websiteUrl, String pageTitle, String parentPage, String childPage,
                          String fullPath, String action, String testedUrl, Long durationMs, String failedResponseLog,
                          Integer statusCode, String status, String screenshotPath) {
            this.dateTime = dateTime;
            this.websiteUrl = websiteUrl;
            this.pageTitle = pageTitle;
            this.parentPage = parentPage;
            this.childPage = childPage;
            this.fullPath = fullPath;
            this.action = action;
            this.testedUrl = testedUrl;
            this.durationMs = durationMs;
            this.failedResponseLog = failedResponseLog;
            this.statusCode = statusCode;
            this.status = status;
            this.screenshotPath = screenshotPath;
        }

        public List<Object> toRow() {
            return Arrays.asList(
                    dateTime, websiteUrl, pageTitle, parentPage, childPage, fullPath, action, testedUrl,
                    durationMs != null ? durationMs : 0,
                    failedResponseLog != null ? failedResponseLog : "",
                    statusCode != null ? statusCode : 0,
                    status != null ? status : "UNKNOWN",
                    screenshotPath != null ? screenshotPath : ""
            );
        }
    }

    static class MainUrlStatus {
        private boolean accessible;
        private int statusCode;
        private String message;
        private String redirectedUrl;
        private long responseTime;
        private String usedUrl;
        private boolean usedFallback;
        private String fallbackMessage;

        public MainUrlStatus(boolean accessible, int statusCode, String message, String redirectedUrl,
                             long responseTime, String usedUrl, boolean usedFallback, String fallbackMessage) {
            this.accessible = accessible;
            this.statusCode = statusCode;
            this.message = message;
            this.redirectedUrl = redirectedUrl;
            this.responseTime = responseTime;
            this.usedUrl = usedUrl;
            this.usedFallback = usedFallback;
            this.fallbackMessage = fallbackMessage;
        }

        public boolean isAccessible() { return accessible; }
        public int getStatusCode() { return statusCode; }
        public String getMessage() { return message; }
        public String getRedirectedUrl() { return redirectedUrl; }
        public long getResponseTime() { return responseTime; }
        public String getUsedUrl() { return usedUrl; }
        public boolean isUsedFallback() { return usedFallback; }
        public String getFallbackMessage() { return fallbackMessage; }
    }

    public static void main(String[] args) {
        List<String> websites = Arrays.asList("https://www.brandpos.io");

        executorService = Executors.newFixedThreadPool(MAX_PARALLEL_CHECKS);

        try {
            DateTimeFormatter sheetFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
            String currentDateTime = LocalDateTime.now().format(sheetFormatter);
            mainSheetName = "Test_Report_" + currentDateTime;

            boolean sheetsInitialized = initializeGoogleSheetsService();

            if (!sheetsInitialized) {
                System.err.println("\nWARNING: Google Sheets integration not available. Continuing without reporting...\n");
            } else {
                createNewSheet();
                setupSheetHeaders();
                ensureSheetHasEnoughRows(1000);
            }

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
            String timestamp = LocalDateTime.now().format(formatter);
            screenshotDir = "failed_screenshots_" + timestamp;

            try {
                Files.createDirectories(Paths.get(screenshotDir));
                System.out.println("Screenshot directory created: " + screenshotDir);
            } catch (IOException e) {
                System.err.println("Could not create screenshot directory: " + e.getMessage());
            }

            ChromeOptions options = new ChromeOptions();
            options.addArguments("--remote-allow-origins=*");
            options.addArguments("--disable-dev-shm-usage");
            options.addArguments("--no-sandbox");
            options.addArguments("--disable-blink-features=AutomationControlled");
            options.addArguments("--headless");
            options.addArguments("--window-size=1920,1080");
            options.addArguments("--disable-notifications");
            options.addArguments("--disable-popup-blocking");
            options.addArguments("--disable-geolocation");
            options.addArguments("--disable-logging");
            options.addArguments("--log-level=3");
            options.addArguments("--silent");

            Map<String, Object> prefs = new HashMap<>();
            prefs.put("profile.default_content_setting_values.notifications", 2);
            prefs.put("profile.default_content_setting_values.geolocation", 2);
            prefs.put("profile.default_content_setting_values.media_stream_mic", 2);
            prefs.put("profile.default_content_setting_values.media_stream_camera", 2);
            prefs.put("profile.default_content_setting_values.popups", 2);
            options.setExperimentalOption("prefs", prefs);

            driver = new ChromeDriver(options);
            driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(5));
            driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(15));
            actions = new Actions(driver);
            jsExecutor = (JavascriptExecutor) driver;
            wait = new WebDriverWait(driver, Duration.ofSeconds(5));

            for (String site : websites) {
                urlCache.clear();
                allDiscoveredUrls.clear();

                // Extract base domain
                try {
                    URL url = new URL(site);
                    baseDomain = url.getHost().replace("www.", "");
                } catch (Exception e) {
                    baseDomain = site;
                }

                addEmptyRowBeforeNewWebsite();
                addWebsiteHeaderRow(site);

                MainUrlStatus mainUrlStatus = checkMainUrlWithFallback(site);

                if (!mainUrlStatus.isAccessible()) {
                    recordMainUrlFailure(site, mainUrlStatus);
                    System.out.println("\nSKIPPING: " + site + " - " + mainUrlStatus.getMessage());
                    continue;
                }

                testAllWebsiteUrls(site, mainUrlStatus);
            }

            driver.quit();
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
            }

            System.out.println("\n============================================================");
            System.out.println("Screenshots saved in: " + screenshotDir);
            if (sheetsService != null) {
                System.out.println("Report added to Google Sheet: " + mainSheetName);
            }
            System.out.println("============================================================");

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (driver != null) {
                driver.quit();
            }
            if (executorService != null && !executorService.isShutdown()) {
                executorService.shutdownNow();
            }
        }
    }

    // Check if URL should be skipped (social media, email, phone, files, external)
    static boolean shouldSkipUrl(String url) {
        if (url == null || url.isEmpty() || url.equals("#") || url.equals("/")) {
            return true;
        }

        String lowerUrl = url.toLowerCase();

        // Skip email addresses
        if (lowerUrl.startsWith("mailto:")) {
            System.out.println("  Skipping email: " + url);
            return true;
        }

        // Skip phone numbers
        if (lowerUrl.startsWith("tel:") || lowerUrl.startsWith("callto:")) {
            System.out.println("  Skipping phone: " + url);
            return true;
        }

        // Skip SMS
        if (lowerUrl.startsWith("sms:")) {
            System.out.println("  Skipping SMS: " + url);
            return true;
        }

        // Skip javascript
        if (lowerUrl.startsWith("javascript:")) {
            return true;
        }

        // Skip social media links
        if (SOCIAL_MEDIA_PATTERN.matcher(lowerUrl).find()) {
            System.out.println("  Skipping social media: " + url);
            return true;
        }

        // Skip file downloads
        if (FILE_EXTENSION_PATTERN.matcher(lowerUrl).find()) {
            System.out.println("  Skipping file: " + url);
            return true;
        }

        // Skip anchors (but keep the base URL)
        if (url.startsWith("#")) {
            return true;
        }

        // Skip external domains (only test same domain)
        try {
            URL urlObj = new URL(url);
            String host = urlObj.getHost().replace("www.", "");
            if (!host.equals(baseDomain) && !host.equals(baseDomain.replace("www.", ""))) {
                System.out.println("  Skipping external: " + url);
                return true;
            }
        } catch (Exception e) {
            // If can't parse URL, it might be relative - keep it
        }

        return false;
    }

    static void testAllWebsiteUrls(String baseUrl, MainUrlStatus mainUrlStatus) {
        System.out.println("\n============================================================");
        System.out.println("CRAWLING ALL URLS ON: " + baseUrl);
        System.out.println("Using URL: " + mainUrlStatus.getUsedUrl());
        System.out.println("Domain: " + baseDomain);
        System.out.println("============================================================");

        testResults = new ArrayList<>();
        testedUrls = new HashSet<>();

        try {
            long testStartTime = System.currentTimeMillis();
            System.out.println("Loading website to discover all URLs...");
            driver.get(mainUrlStatus.getUsedUrl());
            Thread.sleep(2000);
            handlePopups();

            // Discover all URLs on the website
            discoverAllUrls();

            System.out.println("\n============================================================");
            System.out.println("Found " + allDiscoveredUrls.size() + " unique URLs to test");
            System.out.println("(Excluding social media, email, phone, files, and external links)");
            System.out.println("============================================================\n");

            // Convert to list for parallel processing
            List<String> urlsToTest = new ArrayList<>(allDiscoveredUrls);

            // Process URLs in parallel
            List<TestResult> results = new CopyOnWriteArrayList<>();
            AtomicInteger processedCount = new AtomicInteger(0);
            AtomicInteger totalItems = new AtomicInteger(urlsToTest.size());

            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (String url : urlsToTest) {
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        TestResult result = testSingleUrl(baseUrl, url);
                        results.add(result);
                        int processed = processedCount.incrementAndGet();
                        String statusSymbol = "PASS".equals(result.status) ? "✓" : "✗";
                        System.out.println(statusSymbol + " [" + processed + "/" + totalItems.get() + "] " +
                                url + " (HTTP " + result.statusCode + ") - " + result.durationMs + "ms");
                    } catch (Exception e) {
                        System.err.println("Error testing " + url + ": " + e.getMessage());
                    }
                }, executorService);
                futures.add(future);
            }

            // Wait for all checks to complete with timeout
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .orTimeout(180, TimeUnit.SECONDS)
                    .join();

            testResults.addAll(results);
            appendResultsToSheet(testResults);

            long passed = testResults.stream().filter(r -> "PASS".equals(r.status)).count();
            long failed = testResults.stream().filter(r -> "FAIL".equals(r.status)).count();
            long totalDuration = System.currentTimeMillis() - testStartTime;

            printSummary(baseUrl, (int)passed, (int)failed, totalDuration, mainUrlStatus);

        } catch (Exception e) {
            System.err.println("Error testing website: " + e.getMessage());
            e.printStackTrace();
        }
    }

    static void discoverAllUrls() {
        try {
            // Find ALL links on the page
            List<WebElement> allLinks = driver.findElements(By.xpath("//a[@href]"));

            System.out.println("Found " + allLinks.size() + " total links. Filtering...");

            for (WebElement link : allLinks) {
                try {
                    String href = link.getAttribute("href");
                    if (href == null || href.isEmpty()) continue;

                    // Skip unwanted URLs
                    if (shouldSkipUrl(href)) continue;

                    // Normalize URL (remove trailing slashes and fragments)
                    String normalizedUrl = normalizeUrl(href);
                    if (normalizedUrl != null && !normalizedUrl.isEmpty()) {
                        allDiscoveredUrls.add(normalizedUrl);
                    }

                } catch (Exception e) {
                    // Skip this link
                }
            }

            // Try to find links within JavaScript (simulate clicking dropdowns)
            expandAllMenus();

            // Check again after expanding menus
            List<WebElement> additionalLinks = driver.findElements(By.xpath("//a[@href]"));
            for (WebElement link : additionalLinks) {
                try {
                    String href = link.getAttribute("href");
                    if (href == null || href.isEmpty()) continue;

                    if (shouldSkipUrl(href)) continue;

                    String normalizedUrl = normalizeUrl(href);
                    if (normalizedUrl != null && !normalizedUrl.isEmpty()) {
                        allDiscoveredUrls.add(normalizedUrl);
                    }
                } catch (Exception e) {}
            }

        } catch (Exception e) {
            System.err.println("Error discovering URLs: " + e.getMessage());
        }
    }

    static String normalizeUrl(String url) {
        try {
            // Remove URL fragments (#anything)
            int fragmentIndex = url.indexOf('#');
            if (fragmentIndex > 0) {
                url = url.substring(0, fragmentIndex);
            }

            // Remove trailing slash
            if (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }

            // Handle relative URLs
            if (url.startsWith("/")) {
                url = "https://" + baseDomain + url;
            } else if (!url.startsWith("http")) {
                // Skip invalid URLs
                return null;
            }

            return url;
        } catch (Exception e) {
            return null;
        }
    }

    static TestResult testSingleUrl(String websiteUrl, String testUrl) {
        long startTime = System.currentTimeMillis();
        String errorLog = "";
        Integer statusCode = null;
        String status = "PASS";
        String screenshotPath = "";
        String pageTitle = "";

        HttpURLConnection conn = null;
        try {
            // Check cache first
            CachedUrlStatus cached = urlCache.get(testUrl);
            if (cached != null && cached.isValid()) {
                statusCode = cached.statusCode;
                if (statusCode != 200 && statusCode != 301 && statusCode != 302) {
                    status = "FAIL";
                    errorLog = String.format("HTTP %d - %s (cached)", statusCode, getHttpStatusMessage(statusCode));
                }
                long duration = System.currentTimeMillis() - startTime;
                String dateTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                return new TestResult(dateTime, websiteUrl, "", "Website", "URL", testUrl, "Direct URL Check",
                        testUrl, duration, errorLog, statusCode, status, "");
            }

            // Use HEAD request for fast checking
            conn = (HttpURLConnection) new URL(testUrl).openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(CONNECTION_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setInstanceFollowRedirects(true);
            conn.connect();
            statusCode = conn.getResponseCode();

            // Cache the result
            urlCache.put(testUrl, new CachedUrlStatus(statusCode));

            // Only load page in browser if it's a success (for screenshot if needed)
            if (statusCode == 200) {
                try {
                    driver.get(testUrl);
                    Thread.sleep(200);
                    try {
                        pageTitle = driver.getTitle();
                        if (pageTitle == null) pageTitle = "";
                    } catch (Exception e) {}
                } catch (Exception e) {
                    // Page load failed but HEAD request worked
                }
            }

            if (statusCode != 200 && statusCode != 301 && statusCode != 302) {
                status = "FAIL";
                errorLog = String.format("HTTP %d - %s", statusCode, getHttpStatusMessage(statusCode));
                if (statusCode >= 400) {
                    screenshotPath = takeScreenshotOnFailure(testUrl, statusCode, null);
                }
            }

        } catch (Exception e) {
            status = "FAIL";
            statusCode = 0;
            errorLog = "Exception: " + e.getMessage();
            screenshotPath = takeScreenshotOnFailure(testUrl, 0, e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        String dateTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        return new TestResult(
                dateTime, websiteUrl, pageTitle, "Website", "URL", testUrl, "Direct URL Check",
                testUrl, duration, errorLog, statusCode, status, screenshotPath
        );
    }

    private static void ensureSheetHasEnoughRows(int requiredRows) {
        if (sheetsService == null) return;
        try {
            Integer sheetId = getSheetIdByName(mainSheetName);
            if (sheetId == null) return;

            Spreadsheet spreadsheet = sheetsService.spreadsheets().get(SPREADSHEET_ID).execute();
            Sheet sheet = null;
            for (Sheet s : spreadsheet.getSheets()) {
                if (s.getProperties().getTitle().equals(mainSheetName)) {
                    sheet = s;
                    break;
                }
            }

            if (sheet == null) return;

            int currentRowCount = sheet.getProperties().getGridProperties().getRowCount();
            if (currentRowCount < requiredRows) {
                List<Request> requests = new ArrayList<>();
                requests.add(new Request().setAppendDimension(new AppendDimensionRequest()
                        .setSheetId(sheetId)
                        .setDimension("ROWS")
                        .setLength(requiredRows - currentRowCount)));

                BatchUpdateSpreadsheetRequest batchUpdateRequest = new BatchUpdateSpreadsheetRequest()
                        .setRequests(requests);
                sheetsService.spreadsheets().batchUpdate(SPREADSHEET_ID, batchUpdateRequest).execute();
                System.out.println("Added " + (requiredRows - currentRowCount) + " rows to the sheet");
            }
        } catch (Exception e) {
            System.err.println("Could not ensure sheet rows: " + e.getMessage());
        }
    }

    private static void addEmptyRowBeforeNewWebsite() {
        if (sheetsService == null) return;
        try {
            if (isFirstWebsite) {
                isFirstWebsite = false;
                return;
            }

            ensureSheetHasEnoughRows(getCurrentRowCount() + 5);

            ValueRange response = sheetsService.spreadsheets().values()
                    .get(SPREADSHEET_ID, mainSheetName + "!A:A")
                    .execute();
            int lastRow = response.getValues() != null ? response.getValues().size() : 1;

            List<List<Object>> emptyRows = Arrays.asList(
                    Arrays.asList("", "", "", "", "", "", "", "", "", "", "", "", ""),
                    Arrays.asList("", "", "", "", "", "", "", "", "", "", "", "", "")
            );
            ValueRange body = new ValueRange().setValues(emptyRows);
            sheetsService.spreadsheets().values()
                    .update(SPREADSHEET_ID, mainSheetName + "!A" + (lastRow + 1) + ":M" + (lastRow + 2), body)
                    .setValueInputOption("RAW")
                    .execute();

        } catch (Exception e) {
            System.err.println("Could not add separator rows: " + e.getMessage());
        }
    }

    private static int getCurrentRowCount() {
        try {
            ValueRange response = sheetsService.spreadsheets().values()
                    .get(SPREADSHEET_ID, mainSheetName + "!A:A")
                    .execute();
            return response.getValues() != null ? response.getValues().size() : 1;
        } catch (Exception e) {
            return 1;
        }
    }

    private static void addWebsiteHeaderRow(String websiteUrl) {
        if (sheetsService == null) return;
        try {
            ensureSheetHasEnoughRows(getCurrentRowCount() + 2);

            ValueRange response = sheetsService.spreadsheets().values()
                    .get(SPREADSHEET_ID, mainSheetName + "!A:A")
                    .execute();
            int lastRow = response.getValues() != null ? response.getValues().size() : 1;

            String headerText = "=== TESTING WEBSITE: " + websiteUrl + " ===";
            List<List<Object>> headerRow = Arrays.asList(Arrays.asList(
                    headerText, "", "", "", "", "", "", "", "", "", "", "", ""
            ));
            ValueRange body = new ValueRange().setValues(headerRow);
            sheetsService.spreadsheets().values()
                    .update(SPREADSHEET_ID, mainSheetName + "!A" + (lastRow + 1) + ":M" + (lastRow + 1), body)
                    .setValueInputOption("RAW")
                    .execute();

            formatHeaderRowForWebsite(lastRow + 1);
            System.out.println("Added website header for: " + websiteUrl);

        } catch (Exception e) {
            System.err.println("Could not add website header: " + e.getMessage());
        }
    }

    private static void formatHeaderRowForWebsite(int rowIndex) throws IOException {
        Integer sheetId = getSheetIdByName(mainSheetName);
        if (sheetId == null) return;

        List<Request> requests = new ArrayList<>();
        requests.add(new Request().setRepeatCell(new RepeatCellRequest()
                .setRange(new GridRange()
                        .setSheetId(sheetId)
                        .setStartRowIndex(rowIndex - 1)
                        .setEndRowIndex(rowIndex))
                .setCell(new CellData().setUserEnteredFormat(
                        new CellFormat()
                                .setTextFormat(new TextFormat().setBold(true))
                                .setBackgroundColor(new Color().setRed(0.9f).setGreen(0.9f).setBlue(0.9f))))
                .setFields("userEnteredFormat.textFormat.bold,userEnteredFormat.backgroundColor")));

        BatchUpdateSpreadsheetRequest batchUpdateRequest = new BatchUpdateSpreadsheetRequest()
                .setRequests(requests);
        sheetsService.spreadsheets().batchUpdate(SPREADSHEET_ID, batchUpdateRequest).execute();
    }

    private static MainUrlStatus checkMainUrlWithFallback(String originalUrl) {
        System.out.println("\nChecking main URL: " + originalUrl);

        String wwwUrl = originalUrl;
        String nonWwwUrl = originalUrl;

        if (originalUrl.contains("://www.")) {
            nonWwwUrl = originalUrl.replace("://www.", "://");
        } else if (originalUrl.contains("://") && !originalUrl.contains("://www.")) {
            String protocol = originalUrl.substring(0, originalUrl.indexOf("://") + 3);
            String domain = originalUrl.substring(originalUrl.indexOf("://") + 3);
            wwwUrl = protocol + "www." + domain;
        }

        System.out.println("  Trying: " + wwwUrl);
        MainUrlStatus primaryStatus = checkSingleUrl(wwwUrl);

        if (primaryStatus.isAccessible()) {
            System.out.println("  SUCCESS: " + wwwUrl + " is accessible");
            return new MainUrlStatus(true, primaryStatus.getStatusCode(), primaryStatus.getMessage(),
                    primaryStatus.getRedirectedUrl(), primaryStatus.getResponseTime(),
                    wwwUrl, false, null);
        }

        System.out.println("  Primary failed, trying fallback: " + nonWwwUrl);
        MainUrlStatus fallbackStatus = checkSingleUrl(nonWwwUrl);

        if (fallbackStatus.isAccessible()) {
            String fallbackMessage = String.format("Primary URL (%s) failed (HTTP %d). Using fallback URL (%s) instead.",
                    wwwUrl, primaryStatus.getStatusCode(), nonWwwUrl);
            System.out.println("  SUCCESS with fallback: " + nonWwwUrl);
            System.out.println("  Note: " + fallbackMessage);
            return new MainUrlStatus(true, fallbackStatus.getStatusCode(), fallbackStatus.getMessage(),
                    fallbackStatus.getRedirectedUrl(), fallbackStatus.getResponseTime(),
                    nonWwwUrl, true, fallbackMessage);
        }

        String finalMessage = String.format("Both URLs failed. Primary: %s (HTTP %d), Fallback: %s (HTTP %d)",
                wwwUrl, primaryStatus.getStatusCode(),
                nonWwwUrl, fallbackStatus.getStatusCode());
        System.out.println("  FAILED: " + finalMessage);
        return new MainUrlStatus(false, fallbackStatus.getStatusCode(), finalMessage,
                null, fallbackStatus.getResponseTime(), nonWwwUrl, true, finalMessage);
    }

    private static MainUrlStatus checkSingleUrl(String url) {
        HttpURLConnection conn = null;
        try {
            CachedUrlStatus cached = urlCache.get(url);
            if (cached != null && cached.isValid()) {
                return new MainUrlStatus(cached.statusCode == 200 || cached.statusCode == 301 || cached.statusCode == 302,
                        cached.statusCode, "Cached result", null, 0, url, false, null);
            }

            long startTime = System.currentTimeMillis();

            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(CONNECTION_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setInstanceFollowRedirects(false);
            conn.connect();

            int statusCode = conn.getResponseCode();
            String redirectedUrl = null;
            String message = "";
            boolean accessible = false;
            long responseTime = System.currentTimeMillis() - startTime;

            if (statusCode == 301 || statusCode == 302 || statusCode == 307 || statusCode == 308) {
                redirectedUrl = conn.getHeaderField("Location");
                message = String.format("Redirected to: %s (HTTP %d)", redirectedUrl, statusCode);
                accessible = true;
            }
            else if (statusCode == 200) {
                message = "OK";
                accessible = true;
            }
            else if (statusCode >= 400 && statusCode < 500) {
                message = getHttpStatusMessage(statusCode) + " - Client Error";
                accessible = false;
            }
            else if (statusCode >= 500 && statusCode < 600) {
                message = getHttpStatusMessage(statusCode) + " - Server Error";
                accessible = false;
            }
            else {
                message = getHttpStatusMessage(statusCode);
                accessible = false;
            }

            if (accessible) {
                urlCache.put(url, new CachedUrlStatus(statusCode));
            }

            return new MainUrlStatus(accessible, statusCode, message, redirectedUrl, responseTime, url, false, null);

        } catch (Exception e) {
            return new MainUrlStatus(false, 0, "Connection failed: " + e.getMessage(), null, 0, url, false, null);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String getHttpStatusMessage(int statusCode) {
        switch (statusCode) {
            case 200: return "OK";
            case 301: return "Moved Permanently";
            case 302: return "Found";
            case 307: return "Temporary Redirect";
            case 308: return "Permanent Redirect";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 410: return "Gone";
            case 429: return "Too Many Requests";
            case 500: return "Internal Server Error";
            case 502: return "Bad Gateway";
            case 503: return "Service Unavailable";
            case 504: return "Gateway Timeout";
            default: return "HTTP " + statusCode;
        }
    }

    private static void recordMainUrlFailure(String originalUrl, MainUrlStatus status) {
        System.out.println("\n============================================================");
        System.out.println("MAIN URL FAILURE: " + originalUrl);
        System.out.println("Status Code: " + status.getStatusCode());
        System.out.println("Message: " + status.getMessage());
        if (status.isUsedFallback()) {
            System.out.println("Fallback Info: " + status.getFallbackMessage());
        }
        System.out.println("Response Time: " + status.getResponseTime() + "ms");
        System.out.println("============================================================");

        if (sheetsService == null) return;

        String dateTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String pageTitle = "MAIN URL - " + (status.getStatusCode() == 0 ? "CONNECTION FAILED" : "HTTP " + status.getStatusCode());
        String parentPage = "N/A";
        String childPage = "N/A";
        String fullPath = "Main URL";
        String action = "Main URL Check";
        String testedUrl = status.getUsedUrl();

        String failedResponseLog = status.getMessage();
        if (status.isUsedFallback()) {
            failedResponseLog = status.getFallbackMessage();
        }

        TestResult mainUrlResult = new TestResult(
                dateTime, originalUrl, pageTitle, parentPage, childPage, fullPath, action, testedUrl,
                status.getResponseTime(), failedResponseLog, status.getStatusCode(),
                status.isAccessible() ? "PASS (with fallback)" : "FAIL",
                "No screenshot - Main URL " + (status.isAccessible() ? "used fallback" : "inaccessible")
        );

        appendResultsToSheet(Collections.singletonList(mainUrlResult));
    }

    private static boolean initializeGoogleSheetsService() {
        try {
            InputStream credentialsStream = null;
            String[] searchPaths = {
                    "src/main/resources/credentials.json",
                    "credentials.json",
                    System.getProperty("user.dir") + "/src/main/resources/credentials.json",
                    System.getProperty("user.dir") + "/credentials.json"
            };

            for (String path : searchPaths) {
                File file = new File(path);
                if (file.exists()) {
                    credentialsStream = new FileInputStream(file);
                    System.out.println("Found credentials at: " + path);
                    break;
                }
            }

            if (credentialsStream == null) {
                System.err.println("\nERROR: credentials.json not found!");
                return false;
            }

            GoogleCredentials credentials = GoogleCredentials.fromStream(credentialsStream)
                    .createScoped(Collections.singletonList(SheetsScopes.SPREADSHEETS));

            final NetHttpTransport HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();

            sheetsService = new Sheets.Builder(HTTP_TRANSPORT, JSON_FACTORY, new HttpCredentialsAdapter(credentials))
                    .setApplicationName(APPLICATION_NAME)
                    .build();

            com.google.api.services.sheets.v4.model.Spreadsheet spreadsheet = sheetsService.spreadsheets().get(SPREADSHEET_ID).execute();
            System.out.println("Google Sheets service initialized");
            System.out.println("Connected to: " + spreadsheet.getProperties().getTitle());

            return true;

        } catch (Exception e) {
            System.err.println("Could not initialize Google Sheets: " + e.getMessage());
            return false;
        }
    }

    private static void createNewSheet() {
        if (sheetsService == null) return;
        try {
            AddSheetRequest addSheetRequest = new AddSheetRequest()
                    .setProperties(new SheetProperties().setTitle(mainSheetName));
            BatchUpdateSpreadsheetRequest batchUpdateRequest = new BatchUpdateSpreadsheetRequest()
                    .setRequests(Collections.singletonList(new Request().setAddSheet(addSheetRequest)));
            sheetsService.spreadsheets().batchUpdate(SPREADSHEET_ID, batchUpdateRequest).execute();
            System.out.println("Created sheet: " + mainSheetName);
        } catch (Exception e) {
            System.err.println("Could not create sheet: " + e.getMessage());
        }
    }

    private static void setupSheetHeaders() {
        if (sheetsService == null) return;
        try {
            List<List<Object>> headers = Arrays.asList(Arrays.asList(
                    "Date and Time", "Website URL", "Page Title", "Parent Page", "Child Page",
                    "Full Navigation Path", "Action", "Tested URL", "Duration (ms)",
                    "Error Details", "Status Code", "Status", "Screenshot Path"
            ));
            ValueRange body = new ValueRange().setValues(headers);
            sheetsService.spreadsheets().values()
                    .update(SPREADSHEET_ID, mainSheetName + "!A1:M1", body)
                    .setValueInputOption("RAW")
                    .execute();

            formatMainHeaderRow();
            System.out.println("Headers added");
        } catch (Exception e) {
            System.err.println("Could not add headers: " + e.getMessage());
        }
    }

    private static void formatMainHeaderRow() throws IOException {
        Integer sheetId = getSheetIdByName(mainSheetName);
        if (sheetId == null) return;

        List<Request> requests = new ArrayList<>();
        requests.add(new Request().setRepeatCell(new RepeatCellRequest()
                .setRange(new GridRange()
                        .setSheetId(sheetId)
                        .setStartRowIndex(0)
                        .setEndRowIndex(1))
                .setCell(new CellData().setUserEnteredFormat(
                        new CellFormat()
                                .setTextFormat(new TextFormat().setBold(true))
                                .setBackgroundColor(new Color().setRed(0.2f).setGreen(0.4f).setBlue(0.6f))))
                .setFields("userEnteredFormat.textFormat.bold,userEnteredFormat.backgroundColor")));

        BatchUpdateSpreadsheetRequest batchUpdateRequest = new BatchUpdateSpreadsheetRequest()
                .setRequests(requests);
        sheetsService.spreadsheets().batchUpdate(SPREADSHEET_ID, batchUpdateRequest).execute();
    }

    private static Integer getSheetIdByName(String sheetName) throws IOException {
        com.google.api.services.sheets.v4.model.Spreadsheet spreadsheet = sheetsService.spreadsheets().get(SPREADSHEET_ID).execute();
        for (Sheet sheet : spreadsheet.getSheets()) {
            if (sheet.getProperties().getTitle().equals(sheetName)) {
                return sheet.getProperties().getSheetId();
            }
        }
        return null;
    }

    private static void appendResultsToSheet(List<TestResult> results) {
        if (sheetsService == null || results.isEmpty()) return;

        try {
            int currentRows = getCurrentRowCount();
            int neededRows = currentRows + results.size() + 5;
            ensureSheetHasEnoughRows(neededRows);

            ValueRange response = sheetsService.spreadsheets().values()
                    .get(SPREADSHEET_ID, mainSheetName + "!A:A")
                    .execute();
            int lastRow = response.getValues() != null ? response.getValues().size() : 1;

            List<List<Object>> rows = new ArrayList<>();
            for (TestResult result : results) {
                rows.add(result.toRow());
            }

            ValueRange body = new ValueRange().setValues(rows);
            sheetsService.spreadsheets().values()
                    .update(SPREADSHEET_ID, mainSheetName + "!A" + (lastRow + 1) + ":M" + (lastRow + rows.size()), body)
                    .setValueInputOption("RAW")
                    .execute();
            System.out.println("Added " + results.size() + " results to sheet");

        } catch (Exception e) {
            System.err.println("Could not add results: " + e.getMessage());
            tryAlternativeAppend(results);
        }
    }

    private static void tryAlternativeAppend(List<TestResult> results) {
        try {
            for (TestResult result : results) {
                ValueRange body = new ValueRange().setValues(Collections.singletonList(result.toRow()));
                sheetsService.spreadsheets().values()
                        .append(SPREADSHEET_ID, mainSheetName + "!A:M", body)
                        .setValueInputOption("RAW")
                        .execute();
            }
            System.out.println("Added " + results.size() + " results using append method");
        } catch (Exception e) {
            System.err.println("Alternative append also failed: " + e.getMessage());
        }
    }

    private static void handlePopups() {
        try {
            String[] cookieSelectors = {
                    "button[aria-label='Accept cookies']", "button[aria-label='Accept']",
                    ".cookie-accept", ".cookie-consent-accept", "#cookie-accept",
                    ".accept-cookies", ".gdpr-accept", ".cc-accept"
            };
            for (String selector : cookieSelectors) {
                try {
                    WebElement acceptBtn = driver.findElement(By.cssSelector(selector));
                    if (acceptBtn.isDisplayed()) {
                        jsExecutor.executeScript("arguments[0].click();", acceptBtn);
                        Thread.sleep(200);
                        break;
                    }
                } catch (Exception e) {}
            }

            try {
                Alert alert = driver.switchTo().alert();
                alert.dismiss();
            } catch (Exception e) {}

            String[] modalSelectors = {".modal .close", ".popup-close", ".popup .close", ".dialog-close"};
            for (String selector : modalSelectors) {
                try {
                    WebElement closeBtn = driver.findElement(By.cssSelector(selector));
                    if (closeBtn.isDisplayed()) {
                        jsExecutor.executeScript("arguments[0].click();", closeBtn);
                        Thread.sleep(200);
                        break;
                    }
                } catch (Exception e) {}
            }

            try {
                Set<String> handles = driver.getWindowHandles();
                if (handles.size() > 1) {
                    String mainHandle = driver.getWindowHandle();
                    for (String handle : handles) {
                        if (!handle.equals(mainHandle)) {
                            driver.switchTo().window(handle);
                            driver.close();
                            driver.switchTo().window(mainHandle);
                            break;
                        }
                    }
                }
            } catch (Exception e) {}

        } catch (Exception e) {}
    }

    static String takeScreenshotOnFailure(String url, int statusCode, Exception error) {
        try {
            String urlName = url.replace("https://", "")
                    .replace("http://", "")
                    .replace("/", "_")
                    .replace(".", "_")
                    .replace("?", "_")
                    .replace("&", "_");

            if (urlName.length() > 100) {
                urlName = urlName.substring(0, 100);
            }

            String timestamp = String.valueOf(System.currentTimeMillis());
            String filename = String.format("FAILED_%s_%d_%s.png", urlName, statusCode, timestamp);
            Path destination = Paths.get(screenshotDir, filename);

            File screenshot = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
            Files.copy(screenshot.toPath(), destination);

            System.out.println("    Screenshot saved: " + filename);
            return destination.toString();

        } catch (Exception e) {
            System.err.println("    Failed to save screenshot: " + e.getMessage());
            return "Screenshot failed: " + e.getMessage();
        }
    }

    static void expandAllMenus() {
        try {
            List<WebElement> clickableMenus = driver.findElements(By.xpath(
                    "//nav//*[contains(@class, 'dropdown-toggle')] | " +
                            "//nav//*[contains(@class, 'menu-toggle')]"
            ));
            for (WebElement menu : clickableMenus) {
                try {
                    if (menu.isDisplayed() && menu.isEnabled()) {
                        jsExecutor.executeScript("arguments[0].click();", menu);
                        Thread.sleep(200);
                    }
                } catch (Exception e) {}
            }
            Thread.sleep(300);
            handlePopups();
        } catch (Exception e) {}
    }

    static void printSummary(String baseUrl, int passed, int failed, long totalDuration, MainUrlStatus mainUrlStatus) {
        System.out.println("\n============================================================");
        System.out.println("SUMMARY: " + baseUrl);
        System.out.println("Used URL: " + mainUrlStatus.getUsedUrl());
        if (mainUrlStatus.isUsedFallback()) {
            System.out.println("NOTE: " + mainUrlStatus.getFallbackMessage());
        }
        System.out.println("============================================================");
        System.out.println("Total URLs tested: " + (passed + failed));
        System.out.println("Passed: " + passed);
        System.out.println("Failed: " + failed);
        if (passed + failed > 0) {
            System.out.println("Pass Rate: " + String.format("%.2f%%", (double) passed / (passed + failed) * 100));
        }
        System.out.println("Duration: " + totalDuration + " ms");
        System.out.println("============================================================");
    }
}