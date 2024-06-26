package io.jenkins.plugins;

import hudson.Launcher;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.util.FormValidation;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.Builder;
import hudson.tasks.BuildStepDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import java.io.IOException;
import jenkins.tasks.SimpleBuildStep;

import org.acegisecurity.Authentication;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundSetter;

//-

import java.io.File;
import okhttp3.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.ArrayList;
import hudson.AbortException;
import java.util.Base64;
import java.util.Map;
import java.util.List;
import com.google.gson.Gson;
import org.json.JSONObject;
import org.json.JSONArray;
import java.nio.charset.StandardCharsets;
import jenkins.model.Jenkins;
import com.google.gson.reflect.TypeToken;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonObject;

import org.jenkinsci.plugins.plaincredentials.StringCredentials;

import hudson.util.Secret;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import hudson.security.ACL;

import com.cloudbees.plugins.credentials.domains.DomainRequirement;

public class CodeThreatBuilder extends Builder implements SimpleBuildStep {

    private final String ctServer;
    private Integer max_number_of_critical;
    private Integer max_number_of_high;
    private Integer sca_max_number_of_critical;
    private Integer sca_max_number_of_high;
    private String scanId;
    private String scanStatus;
    private String report;
    private String weakness_is = "";
    private String condition = "AND";
    private final String project_name;
    private String title = "";
    private String severity = "";

    private Secret password;
    private String username;
    private Secret accessTokenSecret;
    private String fileName;
    private String credentialsId;
    private String organization_name;
    private String policy_name;

    @DataBoundConstructor
    public CodeThreatBuilder(String ctServer, String project_name, String fileName, String credentialsId,
            String organization_name) throws IOException {

        while (ctServer.endsWith("/")) {
            ctServer = ctServer.substring(0, ctServer.length() - 1);
        }
        this.ctServer = ctServer;
        this.fileName = fileName;
        this.project_name = project_name;
        this.credentialsId = credentialsId;
        this.organization_name = organization_name;
    }

    @DataBoundSetter
    public void setMaxNumberOfCritical(Integer max_number_of_critical) {
        this.max_number_of_critical = max_number_of_critical;
    }
    
    @DataBoundSetter
    public void setMaxNumberOfHigh(Integer max_number_of_high) {
        this.max_number_of_high = max_number_of_high;
    }

    @DataBoundSetter
    public void setScaMaxNumberOfCritical(Integer sca_max_number_of_critical) {
        this.sca_max_number_of_critical = sca_max_number_of_critical;
    }

    @DataBoundSetter
    public void setScaMaxNumberOfHigh(Integer sca_max_number_of_high) {
        this.sca_max_number_of_high = sca_max_number_of_high;
    }

    @DataBoundSetter
    public void setWeaknessIs(String weakness_is) {
        this.weakness_is = weakness_is;
    }

    @DataBoundSetter
    public void setCondition(String condition) {
        this.condition = condition;
    }

    @DataBoundSetter
    public void setPolicyName(String policy_name) {
        this.policy_name = policy_name;
    }

    public String getCtServer() {
        return ctServer;
    }

    public String getScanId() {
        return scanId;
    }

    public String getScanStatus() {
        return scanStatus;
    }

    public Integer getMaxNumberOfCritical() {
        return max_number_of_critical;
    }

    public Integer getMaxNumberOfHigh() {
        return max_number_of_high;
    }

    public Integer getScaMaxNumberOfCritical() {
        return sca_max_number_of_critical;
    }

    public Integer getScaMaxNumberOfHigh() {
        return sca_max_number_of_high;
    }

    public String getWeaknessIs() {
        return weakness_is;
    }

    public String getCondition() {
        return condition;
    }

    public String getProjectName() {
        return project_name;
    }

    public String getTitle() {
        return title;
    }

    public String getSeverity() {
        return severity;
    }

    public String getPolicyName() {
        return policy_name;
    }

    public Secret getToken(String username, Secret password) throws IOException {

        OkHttpClient client = new OkHttpClient();
        MediaType mediaType = MediaType.parse("application/json");
        JSONObject json = new JSONObject();
        json.put("client_id", username);
        json.put("client_secret", password);
        RequestBody body = RequestBody.create(mediaType, json.toString());
        Request request = new Request.Builder()
                .url(ctServer + "/api/signin")
                .post(body)
                .build();

        Response response = client.newCall(request).execute();
        if (!response.isSuccessful())
            throw new IOException("Unexpected code " + response);

        ResponseBody body1 = response.body();
        if (body1 == null)
            throw new IOException("Unexpected body to be null");

        ObjectMapper mapper = new ObjectMapper();
        JsonNode jsonNode = mapper.readValue(body1.string(), JsonNode.class);
        return Secret.fromString(jsonNode.get("access_token").asText());
    }

    public String uploadFile(Secret accessTokenSecret, File fullFile) throws IOException {

        OkHttpClient client = new OkHttpClient();
        MediaType mediaType = MediaType.parse("application/zip");
        RequestBody fileBody = RequestBody.create(mediaType, fullFile);
        MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM);
        builder.addFormDataPart("upfile", project_name + ".zip", fileBody);
        builder.addFormDataPart("project", project_name);
        builder.addFormDataPart("from", "jenkins");
        if(policy_name != null){
            builder.addFormDataPart("policy_id", policy_name);
        }
        RequestBody requestBody = builder.build();
        Request request = new Request.Builder()
                .url(ctServer + "/api/plugins/jenkins")
                .post(requestBody)
                .addHeader("Authorization", "Bearer " + accessTokenSecret)
                .addHeader("x-ct-organization", organization_name)
                .addHeader("x-ct-plugin", "jenkins")
                .build();
        Response response = client.newCall(request).execute();
        if (response == null) {
            throw new IOException("Unexpected null response");
        }
        int statusCode = response.code();
        if (!response.isSuccessful()) {
            ResponseBody responseBody = response.body();
            if (responseBody != null) {
                String responseBodyString = responseBody.string();
                if (!responseBodyString.isEmpty()) {
                    ObjectMapper mapper = new ObjectMapper();
                    JsonNode jsonNode = mapper.readTree(responseBodyString);
                    int errorCode = jsonNode.get("code").asInt();
                    String errorMessage = jsonNode.get("message").asText();
                    throw new IOException("Error: " + errorMessage + " (Code: " + errorCode + ")");
                }
            }
            throw new IOException("Unexpected code " + statusCode + " - " + response.message());
        }

        ResponseBody responseBody = response.body();
        if (responseBody == null) {
            throw new IOException("Unexpected null response body");
        }

        ObjectMapper mapper = new ObjectMapper();
        JsonNode jsonNode = mapper.readValue(responseBody.string(), JsonNode.class);
        return jsonNode.get("scan_id").asText();
    }

    public String awaitScan(String scanId, Secret accessTokenSecret) throws IOException {
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url(ctServer + "/api/scan/status/" + scanId)
                .get()
                .addHeader("Authorization", "Bearer " + accessTokenSecret)
                .addHeader("x-ct-organization", organization_name)
                .build();
        Response response = client.newCall(request).execute();
        if (!response.isSuccessful())
            throw new IOException("Unexpected code " + response);

        ResponseBody body1 = response.body();
        if (body1 == null)
            throw new IOException("Unexpected body to be null");
        return body1.string();
    }

    public String endStatus(String scanId, Secret accessTokenSecret, String ctServer, String organization_name, String project_name)
            throws IOException {
        String endpointURL = ctServer + "/api/plugins/helper?sid=" + scanId + "&project_name=" + project_name;
        OkHttpClient client = new OkHttpClient();

        Request request = new Request.Builder()
                .url(endpointURL)
                .get()
                .addHeader("Authorization", "Bearer " + accessTokenSecret)
                .addHeader("x-ct-organization", organization_name)
                .addHeader("x-ct-baseURL", ctServer)
                .addHeader("x-ct-from", "jenkins")
                .build();
        Response response = client.newCall(request).execute();
        if (!response.isSuccessful())
            throw new IOException("Unexpected code " + response);

        ResponseBody responseBody = response.body();
        if (responseBody == null) {
            throw new IOException("Unexpected null response body");
        }

        return responseBody.string();
    }

    public static String convertToHHMMSS(Integer endedAt, Integer startedAt) {

        int durationInMilliseconds = endedAt - startedAt;
        int durationInMinutes = durationInMilliseconds / (1000 * 60);
        int hours = durationInMinutes / 60;
        int minutes = durationInMinutes % 60;
        int seconds = (durationInMilliseconds % (1000 * 60)) / 1000;
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }

    public static String getScore(Integer percentage) {
        ArrayList<HashMap<String, Object>> scores = new ArrayList<>();
        HashMap<String, Object> score;

        score = new HashMap<>();
        score.put("score", "A+");
        score.put("startingPerc", 97);
        score.put("endingPerc", 100);
        scores.add(score);

        score = new HashMap<>();
        score.put("score", "A");
        score.put("startingPerc", 93);
        score.put("endingPerc", 96);
        scores.add(score);

        score = new HashMap<>();
        score.put("score", "A-");
        score.put("startingPerc", 90);
        score.put("endingPerc", 92);
        scores.add(score);

        score = new HashMap<>();
        score.put("score", "B+");
        score.put("startingPerc", 87);
        score.put("endingPerc", 89);
        scores.add(score);

        score = new HashMap<>();
        score.put("score", "B");
        score.put("startingPerc", 83);
        score.put("endingPerc", 86);
        scores.add(score);

        score = new HashMap<>();
        score.put("score", "B-");
        score.put("startingPerc", 80);
        score.put("endingPerc", 82);
        scores.add(score);

        score = new HashMap<>();
        score.put("score", "C+");
        score.put("startingPerc", 77);
        score.put("endingPerc", 79);
        scores.add(score);

        score = new HashMap<>();
        score.put("score", "C");
        score.put("startingPerc", 73);
        score.put("endingPerc", 76);
        scores.add(score);

        score = new HashMap<>();
        score.put("score", "C-");
        score.put("startingPerc", 90);
        score.put("endingPerc", 92);
        scores.add(score);

        score = new HashMap<>();
        score.put("score", "D+");
        score.put("startingPerc", 70);
        score.put("endingPerc", 72);
        scores.add(score);

        score = new HashMap<>();
        score.put("score", "D");
        score.put("startingPerc", 67);
        score.put("endingPerc", 69);
        scores.add(score);

        score = new HashMap<>();
        score.put("score", "D-");
        score.put("startingPerc", 63);
        score.put("endingPerc", 60);
        scores.add(score);

        score = new HashMap<>();
        score.put("score", "C-");
        score.put("startingPerc", 60);
        score.put("endingPerc", 62);
        scores.add(score);

        score = new HashMap<>();
        score.put("score", "F");
        score.put("startingPerc", 0);
        score.put("endingPerc", 59);
        scores.add(score);

        for (int i = 0; i < scores.size(); i++) {
            HashMap<String, Object> score1 = scores.get(i);
            int startingPerc = (int) score1.get("startingPerc");
            int endingPerc = (int) score1.get("endingPerc");

            if (percentage >= startingPerc && percentage <= endingPerc) {
                return score1.get("score").toString();
            }
        }
        return null;
    }

    public String[] newIssue(Secret accessTokenSecret) throws IOException {

        JSONArray historical = new JSONArray();
        historical.put("New Issue");

        JSONObject query = new JSONObject();
        query.put("projectName", project_name);
        query.put("historical", historical);

        String jsonString = query.toString();
        byte[] jsonBytes = jsonString.getBytes(StandardCharsets.UTF_8);
        String encodedQ = Base64.getEncoder().encodeToString(jsonBytes);

        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url(ctServer + "/api/scanlog/issues?q=" + encodedQ + "&pageSize=500")
                .get()
                .addHeader("Authorization", "Bearer " + accessTokenSecret)
                .addHeader("x-ct-organization", organization_name)
                .build();
        Response response = client.newCall(request).execute();
        if (!response.isSuccessful())
            throw new IOException("Unexpected code " + response);

        String headers = response.headers().get("x-ct-pager");
        if (headers == null)
            throw new IOException("Unexpected body to be null");
        byte[] headerBytes = headers.getBytes(StandardCharsets.UTF_8);
        String jsonString1 = new String(Base64.getDecoder().decode(headerBytes), StandardCharsets.UTF_8);
        JSONObject xCtPager = new JSONObject(jsonString1);

        int pages = xCtPager.getInt("pages");
        String pid = xCtPager.getString("id");

        String[] extractedArray = new String[0];
        for (int i = 1; i <= pages; i++) {
            Request newRequest = new Request.Builder()
                    .url(ctServer + "/api/scanlog/issues?q=" + encodedQ + "&pid=" + pid + "&page=" + i)
                    .get()
                    .addHeader("Authorization", "Bearer " + accessTokenSecret)
                    .addHeader("x-ct-organization", organization_name)
                    .build();
            Response newResponse = client.newCall(newRequest).execute();
            if (!response.isSuccessful())
                throw new IOException("Unexpected code " + response);

            ResponseBody body1 = response.body();
            if (body1 == null)
                throw new IOException("Unexpected body to be null");
            JSONArray responseArray = new JSONArray(body1.string());
            extractedArray = new String[responseArray.length()];

            for (int j = 0; j < responseArray.length(); j++) {
                JSONObject item = responseArray.getJSONObject(j);
                extractedArray[j] = item.toString();
            }
        }
        return extractedArray;
    }

    public String[] allIssue(Secret accessTokenSecret) throws IOException {

        JSONObject query = new JSONObject();
        query.put("projectName", project_name);

        String jsonString = query.toString();
        byte[] jsonBytes = jsonString.getBytes(StandardCharsets.UTF_8);
        String encodedQ = Base64.getEncoder().encodeToString(jsonBytes);

        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url(ctServer + "/api/scanlog/issues?q=" + encodedQ + "&pageSize=500")
                .get()
                .addHeader("Authorization", "Bearer " + accessTokenSecret)
                .addHeader("x-ct-organization", organization_name)
                .build();
        Response response = client.newCall(request).execute();
        if (!response.isSuccessful())
            throw new IOException("Unexpected code " + response);

        String headers = response.headers().get("x-ct-pager");
        if (headers == null)
            throw new IOException("Unexpected body to be null");
        byte[] headerBytes = headers.getBytes(StandardCharsets.UTF_8);
        String jsonString1 = new String(Base64.getDecoder().decode(headerBytes), StandardCharsets.UTF_8);
        JSONObject xCtPager = new JSONObject(jsonString1);

        int pages = xCtPager.getInt("pages");
        String pid = xCtPager.getString("id");

        String[] extractedArray = new String[0];
        for (int i = 1; i <= pages; i++) {
            Request newRequest = new Request.Builder()
                    .url(ctServer + "/api/scanlog/issues?q=" + encodedQ + "&pid=" + pid + "&page=" + i)
                    .get()
                    .addHeader("Authorization", "Bearer " + accessTokenSecret)
                    .addHeader("x-ct-organization", organization_name)
                    .build();
            Response newResponse = client.newCall(newRequest).execute();
            if (!response.isSuccessful())
                throw new IOException("Unexpected code " + response);

            ResponseBody body1 = response.body();
            if (body1 == null)
                throw new IOException("Unexpected body to be null");
            JSONArray responseArray = new JSONArray(body1.string());
            extractedArray = new String[responseArray.length()];

            for (int j = 0; j < responseArray.length(); j++) {
                JSONObject item = responseArray.getJSONObject(j);
                extractedArray[j] = item.toString();
            }
        }
        return extractedArray;
    }

    public static ArrayList<String> findWeaknessTitles(String[] arr, String[] keywords) {

        ArrayList<String> failedWeaknesss = new ArrayList<>();
        for (String element : arr) {
            JsonElement jsonElement = new JsonParser().parse(element);
            JsonObject issueState = jsonElement.getAsJsonObject().get("issue_state").getAsJsonObject();
            String weaknessId = issueState.get("weakness_id").getAsString();
            for (String keyword : keywords) {
                if (weaknessId.matches(keyword)) {
                    failedWeaknesss.add(weaknessId);
                    break;
                }
            }
        }
        return failedWeaknesss;
    }

    public List<Map<String, Object>> countAndGroupByTitle(String[] array1) {
        List<Map<String, Object>> nullArr = new ArrayList<Map<String, Object>>();
        if (array1 == null || array1.length == 0) {
            return nullArr;
        }

        List<Map<String, Object>> array = new ArrayList<Map<String, Object>>();
        for (String item : array1) {
            Map<String, Object> map = new HashMap<String, Object>();
            map.put("title", item);
            array.add(map);
        }

        Map<String, Integer> titleCounts = new HashMap<String, Integer>();
        Map<String, String> titleSeverity = new HashMap<String, String>();
        for (Map<String, Object> item : array) {
            Map<String, Map<String, String>> kbFields = (Map<String, Map<String, String>>) item.get("kb_fields");
            String title = kbFields.get("title").get("en");
            String severity = (String) ((Map<String, Object>) item.get("issue_state")).get("severity");
            if (!titleCounts.containsKey(title)) {
                titleCounts.put(title, 0);
                titleSeverity.put(title, severity);
            }
            titleCounts.put(title, titleCounts.get(title) + 1);
        }

        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, Integer> entry : titleCounts.entrySet()) {
            Map<String, Object> item = new HashMap<String, Object>();
            item.put("title", title);
            item.put("count", titleCounts.get(title));
            item.put("severity", titleSeverity.get(title));
            result.add(item);
        }

        return result;
    }

    public static List<Map<String, Object>> groupIssues(String[] arr) {
        Map<String, Integer> titleCount = new HashMap<>();
        Map<String, String> titleSeverity = new HashMap<>();
        List<Map<String, Object>> result = new ArrayList<>();

        for (String issue : arr) {
            Map<String, Object> issueJson = new Gson().fromJson(issue, new TypeToken<Map<String, Object>>() {
            }.getType());
            Map<String, Object> issueState = (Map<String, Object>) issueJson.get("issue_state");
            Map<String, Object> kbFields = (Map<String, Object>) issueJson.get("kb_fields");
            Map<String, Object> title = (Map<String, Object>) kbFields.get("title");
            String titleEn = (String) title.get("en");
            String titleWeaknessId = (String) issueState.get("weakness_id");

            if (titleCount.containsKey(titleWeaknessId)) {
                titleCount.put(titleWeaknessId, titleCount.get(titleWeaknessId) + 1);
            } else {
                titleCount.put(titleWeaknessId, 1);
            }

            titleSeverity.put(titleWeaknessId, (String) issueState.get("severity"));
        }

        for (Map.Entry<String, Integer> entry : titleCount.entrySet()) {
            Map<String, Object> groupedIssue = new HashMap<>();
            groupedIssue.put("title", entry.getKey());
            groupedIssue.put("count", entry.getValue());
            groupedIssue.put("severity", titleSeverity.get(entry.getKey()));
            result.add(groupedIssue);
        }

        return result;
    }

    public static Map<String, Integer> countSeverity(List<Map<String, Object>> list) {
        Map<String, Integer> result = new HashMap<>();
        result.put("critical", 0);
        result.put("high", 0);
        result.put("medium", 0);
        result.put("low", 0);
        for (Map<String, Object> item : list) {
            String severity = (String) item.get("severity");
            int count = (int) item.get("count");
            result.put(severity, result.get(severity) + count);
        }
        return result;
    }

    @Override
    public void perform(Run<?, ?> run, FilePath workspace, EnvVars env, Launcher launcher, TaskListener listener)
            throws InterruptedException, IOException, AbortException {

        List<StandardUsernamePasswordCredentials> credentials = CredentialsProvider.lookupCredentials(
                StandardUsernamePasswordCredentials.class, Jenkins.get(), ACL.SYSTEM,
                new ArrayList<DomainRequirement>());

        for (StandardUsernamePasswordCredentials cred : credentials) {
            if (cred.getId().equals(credentialsId)) {
                username = cred.getUsername();
                password = cred.getPassword();
                break;
            }
        }

        if (username != null) {
            accessTokenSecret = getToken(username, password);
        } else {
            List<StringCredentials> stringCredentials = CredentialsProvider.lookupCredentials(StringCredentials.class,
                    Jenkins.get(), ACL.SYSTEM, new ArrayList<DomainRequirement>());

            for (StringCredentials cred : stringCredentials) {
                if (cred.getId().equals(credentialsId)) {
                    accessTokenSecret = cred.getSecret();
                    break;
                }
            }
        }
        String fullFileName = workspace + File.separator + fileName;
        File fullFile = new File(fullFileName);
        String canonicalFilePath = fullFile.getCanonicalPath();

        listener.getLogger().println("------------------------------");
        listener.getLogger().println("CodeThreat Server: " + ctServer);
        listener.getLogger().println("User: " + username);
        listener.getLogger().println("Project: " + project_name);
        listener.getLogger().println("Organization: " + organization_name);
        listener.getLogger().println("------------------------------");

        String replaceString = null;

        if (canonicalFilePath.indexOf("/private") != -1) {
            replaceString = canonicalFilePath.replace("/private", "");
        }

        if (replaceString != null) {
            if (fullFileName.compareTo(replaceString) != 0) {
                throw new AbortException(" ---> Disallowed file name");
            }
        } else {
            if (fullFileName.compareTo(canonicalFilePath) != 0) {
                throw new AbortException(" ---> Disallowed file name");
            }
        }

        scanId = uploadFile(accessTokenSecret, fullFile);
        scanStatus = awaitScan(scanId, accessTokenSecret);
        listener.getLogger().println(" --- SCAN STARTED --- ");

        while (true) {

            ObjectMapper mapper = new ObjectMapper();
            JsonNode jsonNode = mapper.readValue(scanStatus, JsonNode.class);
            Integer critical = jsonNode.get("severities").get("critical") != null
                    ? jsonNode.get("severities").get("critical").asInt()
                    : 0;
            Integer high = jsonNode.get("severities").get("high") != null
                    ? jsonNode.get("severities").get("high").asInt()
                    : 0;
            Integer medium = jsonNode.get("severities").get("medium") != null
                    ? jsonNode.get("severities").get("medium").asInt()
                    : 0;
            Integer low = jsonNode.get("severities").get("low") != null ? jsonNode.get("severities").get("low").asInt()
                    : 0;
            if (jsonNode.get("state").asText().equals("end")) {
                listener.getLogger()
                        .println("Scan completed successfuly -  " + "(%" + jsonNode.get("progress_data").get("progress").asInt() + ")"
                                + " - Critical: " + critical + " High: " + high + " Medium: " + medium + " Low: "
                                + low);
                listener.getLogger().println("Scan Duration --> "
                        + convertToHHMMSS(jsonNode.get("ended_at").asInt(), jsonNode.get("started_at").asInt()));
                listener.getLogger().println("Risk Score --> " + getScore(jsonNode.get("riskscore").asInt()));

                List<Map<String, Object>> newIssuesData = groupIssues(newIssue(accessTokenSecret));
                Map<String, Integer> newIssuesSeverity = countSeverity(newIssuesData);
                List<Map<String, Object>> allIssuesData = groupIssues(allIssue(accessTokenSecret));
                int totalCountNewIssues = 0;
                for (Map<String, Object> obj : newIssuesData) {
                    totalCountNewIssues += (Integer) obj.get("count");
                }

                int total = 0;
                JsonNode severities = jsonNode.get("severities");
                for (JsonNode severity : severities) {
                    total += severity.asInt();
                }

                List<Map<String, Object>> resultList = new ArrayList<>();
                for (Map<String, Object> item : allIssuesData) {
                    JSONObject query = new JSONObject();
                    query.put("projectName", project_name);
                    query.put("issuename", item.get("title"));

                    String jsonString = query.toString();
                    byte[] jsonBytes = jsonString.getBytes(StandardCharsets.UTF_8);
                    String encodedQ = Base64.getEncoder().encodeToString(jsonBytes);

                    String link = ctServer + "issues?q=" + encodedQ;
                    String count = item.get("count").toString();
                    String title = item.get("title").toString();

                    Map<String, Object> result = new HashMap<>();
                    result.put("link", link);
                    result.put("count", count);
                    result.put("title", title);

                    resultList.add(result);
                }

                report = endStatus(scanId, accessTokenSecret, ctServer, organization_name, project_name);
                JsonNode jsonStatus = mapper.readValue(report, JsonNode.class);
                String resultsLink = ctServer+"/issues?scan_id="+scanId+"&projectName="+project_name+"&tenant="+organization_name;
                String durationTime = jsonStatus.get("report").get("durationTime").asText();
                String riskScore = jsonStatus.get("report").get("riskscore").get("score").asText();
                String fixedIssues = jsonStatus.get("report").get("fixedIssues").asText();
                JsonNode scaDeps = jsonStatus.get("report").get("scaDeps");

                Integer scaCritical = jsonStatus.get("report").get("scaSeverityCounts").get("Critical") != null
                ? jsonStatus.get("report").get("scaSeverityCounts").get("Critical").asInt()
                : 0;
                Integer scaHigh = jsonStatus.get("report").get("scaSeverityCounts").get("High") != null
                ? jsonStatus.get("report").get("scaSeverityCounts").get("High").asInt()
                : 0;

                String[] weaknessArr = weakness_is.split(",");
                ArrayList<String> weaknessIsCount = findWeaknessTitles(newIssue(accessTokenSecret), weaknessArr);

                if (condition == "OR") {
                    if (max_number_of_critical != null && critical > max_number_of_critical) {
                        throw new AbortException(
                                " ---> Critical limit exceeded. [Pipeline interrupted because the FAILED_ARGS arguments you entered were found...]");
                    }
                    if (max_number_of_high != null && high > max_number_of_high) {
                        throw new AbortException(
                                " ---> High limit exceeded. [Pipeline interrupted because the FAILED_ARGS arguments you entered were found...]");
                    }
                    if (weaknessIsCount.size() > 0) {
                        throw new AbortException(
                                " ---> Weaknesses entered in the weakness_is key were found during the scan. [Pipeline interrupted because the FAILED_ARGS arguments you entered were found...]");
                    }
                    if (sca_max_number_of_critical != null && scaCritical > sca_max_number_of_critical) {
                        throw new AbortException(
                                " ---> Sca Critical limit exceeded. [Pipeline interrupted because the FAILED_ARGS arguments you entered were found...]");
                    }
                    if (sca_max_number_of_high != null && scaHigh > sca_max_number_of_high) {
                        throw new AbortException(
                                " ---> Sca High limit exceeded. [Pipeline interrupted because the FAILED_ARGS arguments you entered were found...]");
                    }
                } else if (condition == "AND") {
                    if ((max_number_of_critical != null && critical > max_number_of_critical)
                            || (max_number_of_high != null && high > max_number_of_high)
                            || (sca_max_number_of_critical != null && scaCritical > sca_max_number_of_critical)
                            || (sca_max_number_of_high != null && scaHigh > sca_max_number_of_high)
                            || weaknessIsCount.size() > 0) {
                        throw new AbortException(
                                " ---> Not all conditions are met according to the given arguments. [Pipeline interrupted because the FAILED_ARGS arguments you entered were found...]");
                    }
                }


                run.addAction(new CodeThreatAction(critical, high, medium, low, total, totalCountNewIssues,
                        newIssuesSeverity, resultList, durationTime, riskScore, resultsLink, report, project_name, fixedIssues, scaDeps));
                break;
            } else {
                listener.getLogger()
                        .println("Scanning " + "(%" + jsonNode.get("progress_data").get("progress").asInt() + ")"
                                + " - Critical: " + critical + " High: " + high + " Medium: " + medium + " Low: "
                                + low);

                String[] weaknessArr = weakness_is.split(",");
                ArrayList<String> weaknessIsCount = findWeaknessTitles(newIssue(accessTokenSecret), weaknessArr);

                if (condition == "OR") {
                    if (max_number_of_critical != null && critical > max_number_of_critical) {
                        throw new AbortException(
                                " ---> Critical limit exceeded. [Pipeline interrupted because the FAILED_ARGS arguments you entered were found...]");
                    }
                    if (max_number_of_high != null && high > max_number_of_high) {
                        throw new AbortException(
                                " ---> High limit exceeded. [Pipeline interrupted because the FAILED_ARGS arguments you entered were found...]");
                    }
                    if (weaknessIsCount.size() > 0) {
                        throw new AbortException(
                                " ---> Weaknesses entered in the weakness_is key were found during the scan. [Pipeline interrupted because the FAILED_ARGS arguments you entered were found...]");
                    }
                } else if (condition == "AND") {
                    if ((max_number_of_critical != null && critical > max_number_of_critical)
                            || (max_number_of_high != null && high > max_number_of_high)
                            || weaknessIsCount.size() > 0) {
                        throw new AbortException(
                                " ---> Not all conditions are met according to the given arguments. [Pipeline interrupted because the FAILED_ARGS arguments you entered were found...]");
                    }
                }

                Thread.sleep(3000);
                scanStatus = awaitScan(scanId, accessTokenSecret);
            }
        }
    }

    @Symbol("CodeThreatScan")
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "CodeThreat";
        }

    }

}
