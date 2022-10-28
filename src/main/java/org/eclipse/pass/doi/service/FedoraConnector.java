/*
 *
 * Copyright 2022 Johns Hopkins University
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

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.json.JsonArray;
import javax.json.JsonObject;

import org.dataconservancy.pass.client.PassClient;
import org.dataconservancy.pass.client.PassClientFactory;
import org.dataconservancy.pass.model.Journal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FedoraConnector {
    private static final Logger LOG = LoggerFactory.getLogger(FedoraConnector.class);
    PassClient passClient = PassClientFactory.getPassClient();

    /**
     * This is the only method that the Servlet calls - it orchestrates the process of building
     * a Journal object from the supplied JSON object, seeing if the Journal is present in PASS,
     * creating or updating that Journal if needed, and finally returning the PASS id for the Journal
     *
     * @param xrefJsonObject the supplied crossref JSON object
     * @return the id of the corresponding Journal object in PASS
     */
    String processXrefJsonObject(JsonObject xrefJsonObject) {

        // we have something JSONy, let's build a journal object from it
        LOG.debug("Building pass journal");
        Journal journal = buildPassJournal(xrefJsonObject);

        // and compare it with what we already have in PASS, updating PASS if necessary
        LOG.debug("Comparing journal object with possible PASS version");
        Journal updatedJournal = updateJournalInPass(journal);

        //we return the journal id if we have one
        String journalId = null;
        if (updatedJournal != null) {
            journalId = updatedJournal.getId().toString();
        }

        return journalId;
    }

    /**
     * Takes JSON which represents journal article metadata from Crossref
     * and populates a new Journal object. Currently we take typed issns and the journal
     * name.
     *
     * @param metadata - the JSON metadata from Crossref
     * @return the PASS journal object;s id
     */
    Journal buildPassJournal(JsonObject metadata) {

        LOG.debug("JSON input (from Crossref): " + metadata.toString());

        final String XREF_MESSAGE = "message";
        final String XREF_TITLE = "container-title";
        final String XREF_ISSN_TYPE_ARRAY = "issn-type";
        final String XREF_ISSN_ARRAY = "ISSN";
        final String XREF_ISSN_TYPE = "type";
        final String XREF_ISSN_VALUE = "value";

        Journal passJournal = new Journal();

        JsonObject messageObject = metadata.getJsonObject(XREF_MESSAGE);
        JsonArray containerTitleArray = messageObject.getJsonArray(XREF_TITLE);
        JsonArray issnTypeArray = messageObject.getJsonArray(XREF_ISSN_TYPE_ARRAY);
        JsonArray issnArray = messageObject.getJsonArray(XREF_ISSN_ARRAY);

        if (!containerTitleArray.isNull(0)) {
            passJournal.setJournalName(containerTitleArray.getString(0));
        }

        Set<String> processedIssns = new HashSet<>();

        if (issnTypeArray != null) {
            for (int i = 0; i < issnTypeArray.size(); i++) {
                JsonObject issn = issnTypeArray.getJsonObject(i);

                String type = "";

                //translate crossref issn-type strings to PASS issn-type strings
                if (PassDoiServlet.IssnType.PRINT.getCrossrefTypeString().equals(issn.getString(XREF_ISSN_TYPE))) {
                    type = PassDoiServlet.IssnType.PRINT.getPassTypeString();
                } else if (PassDoiServlet.IssnType.ELECTRONIC.getCrossrefTypeString()
                                                             .equals(issn.getString(XREF_ISSN_TYPE))) {
                    type = PassDoiServlet.IssnType.ELECTRONIC.getPassTypeString();
                }

                //collect the value for this issn
                String value = issn.getString(XREF_ISSN_VALUE);
                processedIssns.add(value);

                if (value.length() > 0) {
                    passJournal.getIssns().add(String.join(":", type, value));
                    LOG.debug("Adding typed ISSN to journal object: " + String.join(":", type, value));
                }
            }
        }

        if (issnArray != null) {
            for (int i = 0; i < issnArray.size(); i++) {
                // if we have issns which were not given as typed, we add them without a type
                String issn = issnArray.getString(i);
                if (!processedIssns.contains(issn)) {
                    passJournal.getIssns().add(":" + issn);//do this to conform with type:value format
                }
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
     *
     * @param journal - the Journal object generated from Crossref metadata
     * @return the updated Journal object stored in PASS if the PASS object needs updating; null if we don't have
     * enough info to create a journal
     */
    Journal updateJournalInPass(Journal journal) {
        LOG.debug("GETTING ISSNS");
        List<String> issns = journal.getIssns();
        LOG.debug("GETTING NAME");
        String name = journal.getJournalName();

        Journal passJournal;

        URI passJournalUri = find(name, issns);

        if (passJournalUri == null) {
            // we don't have this journal in pass yet
            if (name != null && !name.isEmpty() && issns.size() > 0) {
                // we have enough info to make a journal entry
                passJournal = passClient.createAndReadResource(journal, Journal.class);
            } else {
                // do not have enough to create a new journal
                LOG.debug("Not enough info for journal " + name);
                return null;
            }
        } else { //we have a journal, let's see if we can add anything new - just issns atm. we add only if not present
            passJournal = passClient.readResource(passJournalUri, Journal.class);

            if (passJournal != null) {
                //check to see if we can supply issns
                if (!passJournal.getIssns().containsAll(journal.getIssns())) {
                    List<String> newIssnList = Stream.concat(passJournal.getIssns().stream(),
                                                             journal.getIssns().stream()).distinct()
                                                     .collect(Collectors.toList());
                    passJournal.setIssns(newIssnList);
                    passClient.updateResource(passJournal);
                }

            } else {
                String uhoh = "Journal URI " + passJournalUri + " was found, but the object could not be " +
                              "retrieved. This should never happen.";
                LOG.error(uhoh);
                throw new RuntimeException(uhoh);
            }

        }
        //externalize the internal journal id
        String FEDORA_INTERNAL = "http://fcrepo:8080/fcrepo/rest/";
        String internalPrefix = System.getenv("PASS_FEDORA_BASEURL") != null ? System.getenv(
            "PASS_FEDORA_BASEURL") : FEDORA_INTERNAL;
        String FEDORA_EXTERNAL = "https://pass.local/fcrepo/rest/";
        String externalPrefix = System.getenv("PASS_EXTERNAL_FEDORA_BASEURL") != null ? System.getenv(
            "PASS_EXTERNAL_FEDORA_BASEURL") : FEDORA_EXTERNAL;
        internalPrefix = internalPrefix + (internalPrefix.endsWith("/") ? "" : "/");
        externalPrefix = externalPrefix + (externalPrefix.endsWith("/") ? "" : "/");
        LOG.debug("Internal prefix: " + internalPrefix);
        LOG.debug("External prefix: " + externalPrefix);
        String internalUriString = passJournal.getId().toString();
        if (internalUriString.startsWith(internalPrefix)) {
            passJournal.setId(URI.create(internalUriString.replace(internalPrefix, externalPrefix)));
        }
        LOG.debug("passJournal URI: " + passJournal.getId().toString());
        LOG.debug("Returning journal object: " + passJournal);
        return passJournal;
    }

    /**
     * Find a journal in our repository. We take the best match we can find. finder algorithm here should harmonize
     * with the approach in the {@code BatchJournalFinder} in the journal loader code
     *
     * @param name  the name of the journal to be found
     * @param issns the set of issns to find. we assume that the issns stored in the repo are of the format type:value
     * @return the URI of the best match, or null in nothing matches
     */
    URI find(String name, List<String> issns) {

        Set<URI> nameUriSet = passClient.findAllByAttribute(Journal.class, "name", name);
        Map<URI, Integer> uriScores = new HashMap<>();

        if (!issns.isEmpty()) {
            for (String issn : issns) {
                Set<URI> issnList = passClient.findAllByAttribute(Journal.class, "issns", issn);
                if (issnList != null) {
                    for (URI uri : issnList) {
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

        if (uriScores.size() > 0) {
            // we have matches, pick the best one
            Integer highScore = Collections.max(uriScores.values());

            int minimumQualifyingScore = 1;
            // with so little to go on, we may realistically get just one hit

            List<URI> sortedUris = new ArrayList<>();

            for (int i = highScore; i >= minimumQualifyingScore; i--) {
                for (URI uri : uriScores.keySet()) {
                    if (uriScores.get(uri) == i) {
                        sortedUris.add(uri);
                    }
                }
            }

            if (sortedUris.size() > 0) {
                // there are matching journals
                // return the best match
                return sortedUris.get(0);
            }
        }

        // nothing matches, create a new journal
        return null;
    }

}
