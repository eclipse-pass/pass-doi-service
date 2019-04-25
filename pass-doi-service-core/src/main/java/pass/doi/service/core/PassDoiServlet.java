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
package pass.doi.service.core;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.dataconservancy.pass.client.PassClient;
import org.dataconservancy.pass.client.PassClientFactory;
import org.dataconservancy.pass.client.PassJsonAdapter;
import org.dataconservancy.pass.client.adapter.PassJsonAdapterBasic;
import org.dataconservancy.pass.model.Journal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.lang.Thread.sleep;
import static java.util.concurrent.TimeUnit.SECONDS;

@WebServlet(urlPatterns = "/doiServlet")
public class PassDoiServlet extends HttpServlet {

    private static final Logger LOG = LoggerFactory.getLogger(PassDoiServlet.class);

    PassClient passClient = PassClientFactory.getPassClient();
    PassJsonAdapter json = new PassJsonAdapterBasic();

    //longest time we expect it should take to create a Journal object, in ms
    private int cachePeriod = 30000;//milliseconds
    private OkHttpClient client;

    private String BASE_URL = "https://api.crossref.org/";
    private String VERSION = "v1/";
    private String BASIC_PREFIX = "works/";
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

        LOG.debug("Servicing new request");

        //we will call out to crossref and collect the work JSON object
        //the value of this parameter is expected to be already URIencoded
        String doi= request.getParameter("doi");

        //stage 1: verify doi is valid
        if (verify(doi) == null) {//do not have have a valid xref doi
            try (OutputStream out = response.getOutputStream()) {
                String jsonString = Json.createObjectBuilder()
                        .add("error", "Supplied DOI is not in valid Crossref format.")
                        .build()
                        .toString();
                out.write(jsonString.getBytes());
                response.setStatus(400);
                return;
            }
        }

        //stage 2: check map for existence of id
        //put doi on map if absent. add id value when we get it
        if (activeJobs.contains(doi)) {
            try (OutputStream out = response.getOutputStream()) {
                String jsonString = Json.createObjectBuilder()
                        .add("error", "There is already an active request for " + doi + "; try again later.")
                        .build()
                        .toString();
                out.write(jsonString.getBytes());
                response.setStatus(429);
                return;
            }

        } else  {//this DOI is not actively being processed
            //let's temporarily prohibit new requests for this DOI
            activeJobs.add(doi);
            Thread t = new Thread(new ExpiringLock(doi, cachePeriod));
            t.start();
        }


        //stage 3: try to get crossref record, catch errors first, and halt processing
        String xrefJsonString = retrieveXrefMetdata(doi);
        if (xrefJsonString == null) {
            try (OutputStream out = response.getOutputStream()) {
                String jsonString = Json.createObjectBuilder()
                        .add("error", "There was an IO exception opening the output stream for the Crossref request for " + doi)
                        .build()
                        .toString();
                out.write(jsonString.getBytes());
                response.setStatus(500);
            }
        } else if (xrefJsonString.equals("Resource not found.")) {
            try (OutputStream out = response.getOutputStream()) {
                String jsonString = Json.createObjectBuilder()
                        .add("error", "The resource for this DOI could not be found on Crossref.")
                        .build()
                        .toString();
                out.write(jsonString.getBytes());
                response.setStatus(404);
            }
        } else {//have a non-empty string to process
            //we probably have something JSONy at this point. Let's build a journal object from it
            Journal journal = buildPassJournal(xrefJsonString);

            //and compare it with what we already have in PASS, updating PASS if necessary
            String journalId = updateJournalInPass(journal).getId().toString();

            if (journalId != null) {
                response.setContentType("application/json");
                response.setCharacterEncoding("utf-8");

                try (OutputStream out = response.getOutputStream()) {
                    String jsonString = Json.createObjectBuilder()
                            .add("journal-id", journalId)
                            .add("crossref", xrefJsonString)
                            .build()
                            .toString();

                    out.write(jsonString.getBytes());
                    response.setStatus(200);
                }
            } else {//journal id is null - this should never happen unless Crosssref journal is insufficient
                response.setContentType("application/json");
                response.setCharacterEncoding("utf-8");

                try (OutputStream out = response.getOutputStream()) {
                    String jsonString = Json.createObjectBuilder()
                            .add("error", "Insufficient information to locate or specify a journal entry.")
                            .build()
                            .toString();
                    out.write(jsonString.getBytes());
                    response.setStatus(422);
                 }
             }
        }
        activeJobs.remove(doi);
    }



    /**
     * consult crossref to get a works object for a supplied doi
     * @param doi - the supplied doi string, prefix trimmed if necessary
     * @return a string representing the works object if successful; an empty string if not found; null if IO exception
     */
    String retrieveXrefMetdata(String doi) {
        HttpUrl.Builder urlBuilder = HttpUrl.parse(BASE_URL + VERSION + BASIC_PREFIX + doi).newBuilder();
        String url = urlBuilder.build().toString();
        Request okHttpRequest = new Request.Builder()
                .url(url)
                .addHeader("User-Agent", MAILTO)
                .build();
        Call call = client.newCall(okHttpRequest);
        StringBuffer stringBuffer = new StringBuffer();
        try (Response okHttpResponse = call.execute()) {
            String line;
            BufferedReader reader = new BufferedReader(okHttpResponse.body().charStream());
            while ((line = reader.readLine()) != null) {
                stringBuffer.append(line);
            }
        } catch (IOException e) {
            return null;
        }
        return stringBuffer.toString();
    }

    /**
     * Takes JSON which represents journal article metadata from Crossref
     * and populates a new Journal object. Currently we take typed issns and the journal
     * name.
     * @param jsonInput - the JSON metadata from Crossref
     * @return the PASS journal object
     */
    Journal buildPassJournal(String jsonInput) {

        final String XREF_MESSAGE = "message";
        final String XREF_TITLE = "container-title";
        final String XREF_ISSN_TYPE_ARRAY = "issn-type";
        final String XREF_ISSN_TYPE = "type";
        final String XREF_ISSN_VALUE = "value";


        Journal  passJournal = new Journal();

        JsonReader jsonReader;
        jsonReader = Json.createReader(new StringReader(jsonInput));
        JsonObject crossrefMetadata = jsonReader.readObject();
        JsonObject messageObject = crossrefMetadata.getJsonObject(XREF_MESSAGE);
        JsonArray containerTitleArray = messageObject.getJsonArray(XREF_TITLE);
        JsonArray issnTypeArray = messageObject.getJsonArray(XREF_ISSN_TYPE_ARRAY);

        if (!containerTitleArray.isNull(0)) {
            passJournal.setName(containerTitleArray.getString(0));
        }

        for (int i=0; i < issnTypeArray.size(); i++) {
            JsonObject issn = issnTypeArray.getJsonObject(i);

            String type="";

            //translate crossref issn-type strings to PASS issn-type strings
            if (IssnType.PRINT.getCrossrefTypeString().equals(issn.getString(XREF_ISSN_TYPE))) {
                type = IssnType.PRINT.getPassTypeString();
            } else if (IssnType.ELECTRONIC.getCrossrefTypeString().equals(issn.getString(XREF_ISSN_TYPE))) {
                type = IssnType.ELECTRONIC.getPassTypeString();
            }

            //collect the value for this issn
            String value = issn.getString(XREF_ISSN_VALUE);

            if (value.length() > 0) {
                passJournal.getIssns().add(String.join(":", type, value));
            }
        }

        passJournal.setId(null); // we don't need this
        return passJournal;
    }

    /**
     * Take a Journal object constructed from Crossref metadata, and compare it with the
     * version of this object which we have in PASS. Construct the most complete Journal
     * object possible from the two sources - PASS objects are more authoritative. Use the
     * Crossref version if we don't have it already in PASS. Store the resulting object in PASS.
     * @param journal - the Journal object generated from Crossref metadata
     * @return the updated Journal object, stored in PASS if the PASS object needs updating; null if we don't have
     * enough info to create a journal
     */
    Journal updateJournalInPass(Journal journal) {
        List<String> issns = journal.getIssns();
        String name = journal.getName();

        Journal passJournal;

        URI passJournalUri = find(name, issns);

        if (passJournalUri == null) {//we don't have this journal in pass yet
            if(name != null && !name.isEmpty() && issns.size()>0) {//we have enough info to make a journal entry
                passJournal = passClient.createAndReadResource(journal, Journal.class);
            } else {//do not have enough to create a new journal
                return null;
            }
        } else { //we have a journal, let's see if we can add anything new - title or issns. we add only if not present
            boolean update = false;
            passJournal = passClient.readResource(passJournalUri, Journal.class);
            if (passJournal != null) {

                //check to see if we can supply a journal name
                if ((passJournal.getName() == null || passJournal.getName().isEmpty()) && (!(journal.getName() == null) && !journal.getName().isEmpty())) {
                    passJournal.setName(journal.getName());
                    update = true;
                }

                //check to see if we can supply issns
                if (!passJournal.getIssns().containsAll(journal.getIssns())) {
                    List<String> newIssnList = Stream.concat(passJournal.getIssns().stream(), journal.getIssns().stream()).distinct().collect(Collectors.toList());
                    passJournal.setIssns(newIssnList);
                    update = true;
                }

                if (update) {
                    passClient.updateResource(passJournal);
                }
            } else {
                throw new RuntimeException("URI for journal was found, but the object could not be retrieved. This should never happen.");
            }

        }
        return passJournal;
    }

    /**
     * Find a journal in our repository. We take the best match we can find. finder algorithm here should harmonize
     * with the approach in the {@code BatchJournalFinder} in the journal loader code
     * @param name the name of the journal to be found
     * @param issns the set of issns to find. we assume that the issns stored in the repo are og the format type:value
     * @return the URI of the best match, or null in nothing matches
     */
    URI find(String name, List<String> issns) {

        Set<URI> nameUriSet = passClient.findAllByAttribute(Journal.class, "name", name);
        Map<URI, Integer> uriScores = new HashMap<>();

        if (!issns.isEmpty()) {
            for (String issn : issns) {
                Set<URI> issnList = passClient.findAllByAttribute(Journal.class, "issns", issn);
                if (issnList != null) {
                    for(URI uri : issnList){
                        Integer i = uriScores.putIfAbsent(uri, 1);
                        if (i != null) {
                            uriScores.put(uri, i + 1);
                        }
                    }
                }
            }
        }

        if (nameUriSet != null) {
            for (URI uri : nameUriSet) {
                Integer i = uriScores.putIfAbsent(uri, 1);
                if (i != null) {
                    uriScores.put(uri, i + 1);
                }
            }
        }

        if(uriScores.size()>0) {//we have matches, pick the best one
            Integer highScore = Collections.max(uriScores.values());
            int minimumQualifyingScore = 1;//with so little to go on, we may realistically get just one hit
            List<URI> sortedUris = new ArrayList<>();

            for (int i = highScore; i >= minimumQualifyingScore; i--) {
                for (URI uri : uriScores.keySet()) {
                    if(uriScores.get(uri) == i) {
                        sortedUris.add(uri);
                    }
                }
            }

            if (sortedUris.size() > 0 ) {// there are matching journals
                return sortedUris.get(0); //return the best match
            }
        } //nothing matches, create a new journal
        return null;
    }

    /**
     * check to see whether supplied DOI is in Crossref format after splitting off a possible prefix
     * @return the valid suffix, or null if invalid
     */
    String verify(String doi) {
        if (doi == null) {
            return null;
        }
        String criterion = "doi.org/";
        int i = doi.indexOf(criterion);
        String suffix = i>=0 ? doi.substring(i + criterion.length()) : doi;

        Pattern pattern = Pattern.compile("^10\\.\\d{4,9}/[-._;()/:a-zA-Z0-9]+$");

        Matcher matcher = pattern.matcher(suffix);
        return matcher.matches() ? suffix : null;
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