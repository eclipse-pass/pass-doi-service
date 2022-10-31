/*
 *
 * Copyright 2019 Johns Hopkins University
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.eclipse.pass.doi.service;

import static java.lang.Thread.sleep;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.stream.JsonParsingException;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@WebServlet(urlPatterns = "/journal")
public class PassDoiServlet extends HttpServlet {

    private static final Logger LOG = LoggerFactory.getLogger(PassDoiServlet.class);

    FedoraConnector fedoraConnector = new FedoraConnector();

    private OkHttpClient client;

    private String BASE_URL = "https://api.crossref.org/";
    private String VERSION = "v1/";
    private String BASIC_PREFIX = "works/";
    //some defaults
    private String MAILTO = "pass@jhu.edu";

    private Set<String> activeJobs = new HashSet<>();

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        builder.connectTimeout(30, SECONDS);
        builder.readTimeout(30, SECONDS);
        builder.writeTimeout(30, SECONDS);
        client = builder.build();
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
        throws IOException {

        response.setContentType("application/json");
        response.setCharacterEncoding("utf-8");

        LOG.info("Servicing new request ... ");
        LOG.debug("Context path: " + request.getContextPath() + "; query string " + request.getQueryString());

        //we will call out to crossref and collect the work JSON object
        //the value of this parameter is expected to be already URIencoded
        String doi = request.getParameter("doi");

        //stage 1: verify doi is valid
        if (verify(doi) == null) {
            // do not have have a valid xref doi
            try (OutputStream out = response.getOutputStream()) {
                JsonObject jsonObject = Json.createObjectBuilder()
                                            .add("error", "Supplied DOI is not in valid Crossref format.")
                                            .build();
                out.write(jsonObject.toString().getBytes());
                response.setStatus(400);
                return;
            }
        }

        //stage 2: check cache map for existence of doi
        //put doi on map if absent
        if (activeJobs.contains(doi)) {
            try (OutputStream out = response.getOutputStream()) {
                String message = "There is already an active request for " + doi;
                JsonObject jsonObject = Json.createObjectBuilder()
                                            .add("error", message + "; try again later.")
                                            .build();
                out.write(jsonObject.toString().getBytes());
                response.setStatus(429);
                LOG.info(message);
                return;
            }

        } else {
            // this DOI is not actively being processed
            // let's temporarily prohibit new requests for this DOI
            activeJobs.add(doi);
            //longest time we expect it should take to create a Journal object, in ms
            //milliseconds
            int cachePeriod = 30000;
            Thread t = new Thread(new ExpiringLock(doi, cachePeriod));
            t.start();
        }

        //stage 3: try to get crossref record, catch errors first, and halt processing
        JsonObject xrefJsonObject = retrieveXrefMetdata(doi);
        if (xrefJsonObject == null) {
            try (OutputStream out = response.getOutputStream()) {
                String message = "There was an error getting the metadata from Crossref for " + doi;
                JsonObject jsonObject = Json.createObjectBuilder()
                                            .add("error", message)
                                            .build();
                out.write(jsonObject.toString().getBytes());
                response.setStatus(500);
                LOG.info(message);
            }
        } else if (xrefJsonObject.getJsonString("error") != null) {
            int responseCode;
            String message;
            if (xrefJsonObject.getString("error").equals("Resource not found.")) {
                responseCode = 404;
                message = "The resource for DOI " + doi + " could not be found on Crossref.";
            } else {
                responseCode = 500;
                message = "A record for this resource could not be found on Crossref: " +
                          xrefJsonObject.getJsonString("error");
            }
            try (OutputStream out = response.getOutputStream()) {
                JsonObject jsonObject = Json.createObjectBuilder()
                                            .add("error", message)
                                            .build();
                out.write(jsonObject.toString().getBytes());
                response.setStatus(responseCode);
                LOG.info(message);
            }
        } else {
            // have a non-empty string to process
            String journalId = fedoraConnector.resolveJournal(xrefJsonObject);
            if (journalId != null) {

                try (OutputStream out = response.getOutputStream()) {
                    JsonObject jsonObject = Json.createObjectBuilder()
                                                .add("journal-id", journalId)
                                                .add("crossref", xrefJsonObject)
                                                .build();

                    out.write(jsonObject.toString().getBytes());
                    response.setStatus(200);
                    LOG.info("Returning result for DOI " + doi);
                }

            } else {
                // journal id is null - this should never happen unless Crosssref journal is insufficient
                // for example, if a book doi ws supplied which has no issns

                try (OutputStream out = response.getOutputStream()) {
                    String message = "Insufficient information to locate or specify a journal entry.";
                    JsonObject jsonObject = Json.createObjectBuilder()
                                                .add("error", message)
                                                .build();

                    out.write(jsonObject.toString().getBytes());
                    response.setStatus(422);
                    LOG.info(message);
                }
            }
        }
        activeJobs.remove(doi);
    }

    /**
     * consult crossref to get a works object for a supplied doi
     *
     * @param doi - the supplied doi string, prefix trimmed if necessary
     * @return a string representing the works object if successful; an empty string if not found; null if IO exception
     */
    JsonObject retrieveXrefMetdata(String doi) {
        String agent = System.getenv("PASS_DOI_SERVICE_MAILTO") != null ? System.getenv(
            "PASS_DOI_SERVICE_MAILTO") : MAILTO;

        HttpUrl.Builder urlBuilder = HttpUrl.parse(BASE_URL + VERSION + BASIC_PREFIX + doi).newBuilder();
        String url = urlBuilder.build().toString();
        Request okHttpRequest = new Request.Builder()
            .url(url)
            .addHeader("User-Agent", agent)
            .build();
        Call call = client.newCall(okHttpRequest);
        JsonReader reader;
        JsonObject xrefJsonObject;
        String responseString = null;

        try (Response okHttpResponse = call.execute()) {
            responseString = okHttpResponse.body().string();
            reader = Json.createReader(new StringReader(responseString));
            xrefJsonObject = reader.readObject();
            reader.close();
            return xrefJsonObject;
        } catch (JsonParsingException e) {
            if (responseString != null) {
                return Json.createObjectBuilder()
                           .add("error", responseString)
                           .build();
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    /**
     * check to see whether supplied DOI is in Crossref format after splitting off a possible prefix
     *
     * @return the valid suffix, or null if invalid
     */
    String verify(String doi) {
        if (doi == null) {
            return null;
        }
        String criterion = "doi.org/";
        int i = doi.indexOf(criterion);
        String suffix = i >= 0 ? doi.substring(i + criterion.length()) : doi;

        Pattern pattern = Pattern.compile("^10\\.\\d{4,9}/[-._;()/:a-zA-Z0-9]+$");

        Matcher matcher = pattern.matcher(suffix);
        return matcher.matches() ? suffix : null;
    }

    /**
     * a convenience enum for translating type strings for issns
     */
    public enum IssnType {
        PRINT,
        ELECTRONIC;

        static {
            // these values represent how types are stored on the issn field for the PASS Journal object
            PRINT.passTypeString = "Print";
            ELECTRONIC.passTypeString = "Online";
        }

        static {
            // these values represent how issn types are presented in Crossref metadata
            PRINT.crossrefTypeString = "print";
            ELECTRONIC.crossrefTypeString = "electronic";
        }

        private String passTypeString;
        private String crossrefTypeString;

        public String getPassTypeString() {
            return passTypeString;
        }

        public String getCrossrefTypeString() {
            return crossrefTypeString;
        }
    }

    /**
     * A class to manage locking so that an active process for a DOI will finish executing before
     * another one begins
     */
    public class ExpiringLock implements Runnable {
        private String key;
        private int duration;

        ExpiringLock(String key, int duration) {
            this.key = key;
            this.duration = duration;
        }

        public void run() {
            try {
                sleep(duration);
                activeJobs.remove(key);
            } catch (InterruptedException e) {
                activeJobs.remove(key);
            }
        }

    }
}
