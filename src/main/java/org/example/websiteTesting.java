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

    // Class to store menu item with full hierarchy information
    static class MenuItem {
        private String url;
        private String pageTitle;
        private String parentPage;
        private String childPage;
        private String fullPath;
        private int level;

        public MenuItem(String url, String pageTitle, String parentPage, String childPage, String fullPath, int level) {
            this.url = url;
            this.pageTitle = pageTitle;
            this.parentPage = parentPage;
            this.childPage = childPage;
            this.fullPath = fullPath;
            this.level = level;
        }

        public String getUrl() { return url; }
        public String getPageTitle() { return pageTitle; }
        public String getParentPage() { return parentPage; }
        public String getChildPage() { return childPage; }
        public String getFullPath() { return fullPath; }
    }

    public static void main(String[] args) {
        List<String> websites = Arrays.asList(
                "https://www.brandpos.io"
        );

        try {
            // Create sheet name with date and time
            DateTimeFormatter sheetFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
            String currentDateTime = LocalDateTime.now().format(sheetFormatter);
            mainSheetName = "Test_Report_" + currentDateTime;

            // Initialize Google Sheets service with Service Account
            boolean sheetsInitialized = initializeGoogleSheetsService();

            if (!sheetsInitialized) {
                System.err.println("\nWARNING: Google Sheets integration not available. Continuing without reporting...\n");
            } else {
                createNewSheet();
                setupSheetHeaders();
                ensureSheetHasEnoughRows(1000);
            }

            // Create local screenshot directory
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
            String timestamp = LocalDateTime.now().format(formatter);
            screenshotDir = "failed_screenshots_" + timestamp;

            try {
                Files.createDirectories(Paths.get(screenshotDir));
                System.out.println("Screenshot directory created: " + screenshotDir);
            } catch (IOException e) {
                System.err.println("Could not create screenshot directory: " + e.getMessage());
            }

            // Chrome options
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

            Map<String, Object> prefs = new HashMap<>();
            prefs.put("profile.default_content_setting_values.notifications", 2);
            prefs.put("profile.default_content_setting_values.geolocation", 2);
            prefs.put("profile.default_content_setting_values.media_stream_mic", 2);
            prefs.put("profile.default_content_setting_values.media_stream_camera", 2);
            prefs.put("profile.default_content_setting_values.popups", 2);
            options.setExperimentalOption("prefs", prefs);

            driver = new ChromeDriver(options);
            actions = new Actions(driver);
            jsExecutor = (JavascriptExecutor) driver;
            wait = new WebDriverWait(driver, Duration.ofSeconds(10));

            for (int i = 0; i < websites.size(); i++) {
                String site = websites.get(i);

                addEmptyRowBeforeNewWebsite();
                addWebsiteHeaderRow(site);

                MainUrlStatus mainUrlStatus = checkMainUrlWithFallback(site);

                if (!mainUrlStatus.isAccessible()) {
                    recordMainUrlFailure(site, mainUrlStatus);
                    System.out.println("\nSKIPPING: " + site + " - " + mainUrlStatus.getMessage());
                    continue;
                }

                testSiteNavigation(site, mainUrlStatus);
            }

            driver.quit();

            System.out.println("\n============================================================");
            System.out.println("Screenshots saved in: " + screenshotDir);
            if (sheetsService != null) {
                System.out.println("Report added to Google Sheet: " + mainSheetName);
            }
            System.out.println("============================================================");

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
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
            System.out.println("Added separator rows before new website");

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
        try {
            long startTime = System.currentTimeMillis();

            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
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

            conn.disconnect();
            return new MainUrlStatus(accessible, statusCode, message, redirectedUrl, responseTime, url, false, null);

        } catch (Exception e) {
            return new MainUrlStatus(false, 0, "Connection failed: " + e.getMessage(), null, 0, url, false, null);
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
                        Thread.sleep(500);
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
                        Thread.sleep(300);
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

    static void testSiteNavigation(String baseUrl, MainUrlStatus mainUrlStatus) {
        System.out.println("\n============================================================");
        System.out.println("TESTING: " + baseUrl);
        System.out.println("Using URL: " + mainUrlStatus.getUsedUrl());
        if (mainUrlStatus.isUsedFallback()) {
            System.out.println("NOTE: " + mainUrlStatus.getFallbackMessage());
        }
        System.out.println("============================================================");

        testResults = new ArrayList<>();
        testedUrls = new HashSet<>();

        try {
            long testStartTime = System.currentTimeMillis();
            System.out.println("Loading website...");
            driver.get(mainUrlStatus.getUsedUrl());
            Thread.sleep(2000);
            handlePopups();
            Thread.sleep(1000);
            expandAllMenus();

            // Get all menu items with their exact parent and child names
            List<MenuItem> menuItems = getAllMenuItemsWithHierarchy();

            System.out.println("\nFound " + menuItems.size() + " menu items");

            for (MenuItem menuItem : menuItems) {
                TestResult result = testUrlStatusWithDetails(baseUrl, menuItem);
                testResults.add(result);
                String statusSymbol = "PASS".equals(result.status) ? "✓" : "✗";
                System.out.println(statusSymbol + " " + menuItem.fullPath + " -> " + menuItem.url +
                        " (" + result.statusCode + ") - " + result.durationMs + "ms");
                Thread.sleep(200);
            }

            appendResultsToSheet(testResults);

            long passed = testResults.stream().filter(r -> "PASS".equals(r.status)).count();
            long failed = testResults.stream().filter(r -> "FAIL".equals(r.status)).count();
            long totalDuration = System.currentTimeMillis() - testStartTime;

            printSummary(baseUrl, (int)passed, (int)failed, totalDuration, mainUrlStatus);

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Get all menu items with their exact parent and child names as displayed in the menu
    static List<MenuItem> getAllMenuItemsWithHierarchy() {
        List<MenuItem> menuItems = new ArrayList<>();

        try {
            // First, get all top-level menu items
            List<WebElement> topLevelItems = driver.findElements(By.xpath(
                    "//nav//ul[contains(@class, 'menu')]//li[contains(@class, 'menu-item')] | " +
                            "//nav//ul[contains(@class, 'nav')]//li[contains(@class, 'nav-item')] | " +
                            "//nav//ul//li[contains(@class, 'menu-item')]"
            ));

            // If no menu found with class selectors, try more generic approach
            if (topLevelItems.isEmpty()) {
                topLevelItems = driver.findElements(By.xpath(
                        "//nav//a[contains(@href, 'http')] | " +
                                "//header//a[contains(@href, 'http')]"
                ));
            }

            for (WebElement item : topLevelItems) {
                try {
                    // Try to get the link element
                    WebElement link = item;
                    if (!item.getTagName().equals("a")) {
                        try {
                            link = item.findElement(By.tagName("a"));
                        } catch (Exception e) {
                            continue;
                        }
                    }

                    String url = link.getAttribute("href");
                    if (!isValidUrl(url)) continue;

                    // Get the displayed text (exactly as shown in menu)
                    String displayText = link.getText().trim();
                    if (displayText.isEmpty()) {
                        displayText = link.getAttribute("innerText").trim();
                    }
                    if (displayText.isEmpty()) {
                        displayText = "Menu Item";
                    }

                    // Check if this is a top-level or child item
                    boolean isChild = false;
                    String parentName = "Home Page";
                    String childName = displayText;
                    String fullPath = displayText;
                    int level = 1;

                    // Check if this item is inside a sub-menu
                    try {
                        WebElement parentLi = item.findElement(By.xpath(".."));
                        WebElement grandParent = parentLi.findElement(By.xpath(".."));

                        // Check if grandparent has sub-menu class
                        String grandParentClass = grandParent.getAttribute("class");
                        if (grandParentClass != null &&
                                (grandParentClass.contains("sub-menu") ||
                                        grandParentClass.contains("dropdown-menu") ||
                                        grandParentClass.contains("submenu"))) {

                            isChild = true;
                            level = 2;

                            // Get parent menu item
                            WebElement parentLink = grandParent.findElement(By.xpath("preceding-sibling::a | ../a"));
                            parentName = parentLink.getText().trim();
                            if (parentName.isEmpty()) parentName = "Parent Menu";

                            childName = displayText;
                            fullPath = parentName + " > " + childName;
                        }
                    } catch (Exception e) {
                        // Not a child item, keep as top-level
                    }

                    // Also check for nested sub-menus (level 3)
                    try {
                        WebElement parentLi = item.findElement(By.xpath(".."));
                        WebElement grandParent = parentLi.findElement(By.xpath(".."));
                        WebElement greatGrandParent = grandParent.findElement(By.xpath(".."));

                        String greatGrandParentClass = greatGrandParent.getAttribute("class");
                        if (greatGrandParentClass != null &&
                                (greatGrandParentClass.contains("sub-menu") ||
                                        greatGrandParentClass.contains("dropdown-menu"))) {

                            level = 3;
                            // Get the full hierarchy
                            WebElement level1Link = greatGrandParent.findElement(By.xpath("preceding-sibling::a | ../a"));
                            WebElement level2Link = grandParent.findElement(By.xpath("preceding-sibling::a | ../a"));

                            String level1Name = level1Link.getText().trim();
                            String level2Name = level2Link.getText().trim();

                            parentName = level2Name;
                            childName = displayText;
                            fullPath = level1Name + " > " + level2Name + " > " + childName;
                        }
                    } catch (Exception e) {
                        // Not a level 3 item
                    }

                    menuItems.add(new MenuItem(url, displayText, parentName, childName, fullPath, level));

                } catch (Exception e) {
                    // Skip this item
                }
            }

            // Remove duplicates (same URL might appear multiple times)
            Set<String> uniqueUrls = new HashSet<>();
            List<MenuItem> uniqueItems = new ArrayList<>();
            for (MenuItem item : menuItems) {
                if (!uniqueUrls.contains(item.getUrl())) {
                    uniqueUrls.add(item.getUrl());
                    uniqueItems.add(item);
                }
            }

            return uniqueItems;

        } catch (Exception e) {
            System.err.println("Error getting menu hierarchy: " + e.getMessage());
            return new ArrayList<>();
        }
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

    static TestResult testUrlStatusWithDetails(String websiteUrl, MenuItem menuItem) {
        long startTime = System.currentTimeMillis();
        String errorLog = "";
        Integer statusCode = null;
        String status = "PASS";
        String screenshotPath = "";
        String pageTitle = menuItem.getPageTitle();

        try {
            driver.get(menuItem.getUrl());
            Thread.sleep(1000);
            handlePopups();

            try {
                String title = driver.getTitle();
                if (title != null && !title.isEmpty()) pageTitle = title;
            } catch (Exception e) {}

            HttpURLConnection conn = (HttpURLConnection) new URL(menuItem.getUrl()).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(true);
            conn.connect();
            statusCode = conn.getResponseCode();

            if (statusCode != 200 && statusCode != 301 && statusCode != 302) {
                status = "FAIL";
                errorLog = String.format("ERROR on '%s' (Parent: '%s', Child: '%s') - HTTP %d - %s",
                        menuItem.getPageTitle(), menuItem.getParentPage(),
                        menuItem.getChildPage(), statusCode, getHttpStatusMessage(statusCode));
                screenshotPath = takeScreenshotOnFailure(menuItem.getUrl(), statusCode, null);
            }

        } catch (Exception e) {
            status = "FAIL";
            statusCode = 0;
            errorLog = String.format("ERROR on '%s' (Parent: '%s', Child: '%s') - Exception: %s",
                    menuItem.getPageTitle(), menuItem.getParentPage(),
                    menuItem.getChildPage(), e.getMessage());
            screenshotPath = takeScreenshotOnFailure(menuItem.getUrl(), 0, e);
        }

        long duration = System.currentTimeMillis() - startTime;
        String dateTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        return new TestResult(
                dateTime, websiteUrl, pageTitle, menuItem.getParentPage(), menuItem.getChildPage(),
                menuItem.getFullPath(), "Navigation", menuItem.getUrl(),
                duration, errorLog, statusCode, status, screenshotPath
        );
    }

    static void expandAllMenus() {
        try {
            List<WebElement> clickableMenus = driver.findElements(By.xpath(
                    "//nav//*[contains(@class, 'dropdown-toggle')] | " +
                            "//nav//*[contains(@class, 'menu-toggle')] | " +
                            "//nav//button[contains(@class, 'navbar-toggler')]"
            ));
            for (WebElement menu : clickableMenus) {
                try {
                    if (menu.isDisplayed() && menu.isEnabled()) {
                        jsExecutor.executeScript("arguments[0].click();", menu);
                        Thread.sleep(300);
                    }
                } catch (Exception e) {}
            }

            List<WebElement> hoverMenus = driver.findElements(By.xpath(
                    "//nav//li[contains(@class, 'menu-item')] | " +
                            "//nav//li[contains(@class, 'nav-item')]"
            ));
            for (WebElement menu : hoverMenus) {
                try {
                    if (menu.isDisplayed()) {
                        actions.moveToElement(menu).perform();
                        Thread.sleep(200);
                    }
                } catch (Exception e) {}
            }
            Thread.sleep(500);
            handlePopups();
        } catch (Exception e) {}
    }

    static boolean isValidUrl(String url) {
        if (url == null || url.isEmpty() || url.equals("#")) return false;
        if (url.contains("javascript:") || url.contains("mailto:") || url.contains("tel:")) return false;
        if (url.contains("linkedin.com") || url.contains("facebook.com") || url.contains("twitter.com")) return false;
        if (url.contains("instagram.com") || url.contains("youtube.com")) return false;
        if (url.endsWith(".pdf") || url.endsWith(".jpg") || url.endsWith(".png")) return false;
        if (!url.startsWith("http")) return false;
        return true;
    }

    static void printSummary(String baseUrl, int passed, int failed, long totalDuration, MainUrlStatus mainUrlStatus) {
        System.out.println("\n============================================================");
        System.out.println("SUMMARY: " + baseUrl);
        System.out.println("Used URL: " + mainUrlStatus.getUsedUrl());
        if (mainUrlStatus.isUsedFallback()) {
            System.out.println("NOTE: " + mainUrlStatus.getFallbackMessage());
        }
        System.out.println("============================================================");
        System.out.println("Total menu items tested: " + (passed + failed));
        System.out.println("Passed: " + passed);
        System.out.println("Failed: " + failed);
        System.out.println("Pass Rate: " + String.format("%.2f%%", (double) passed / (passed + failed) * 100));
        System.out.println("Duration: " + totalDuration + " ms");
        System.out.println("============================================================");
    }
}

