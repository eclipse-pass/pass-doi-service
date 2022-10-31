package org.eclipse.pass.doi.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.json.JsonArray;
import javax.json.JsonObject;

import com.yahoo.elide.RefreshableElide;
import org.eclipse.pass.object.ElideDataStorePassClient;
import org.eclipse.pass.object.PassClientResult;
import org.eclipse.pass.object.PassClientSelector;
import org.eclipse.pass.object.RSQL;
import org.eclipse.pass.object.model.Journal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ElideConnector {
    private static final Logger LOG = LoggerFactory.getLogger(FedoraConnector.class);

    protected RefreshableElide refreshableElide;

    protected ElideDataStorePassClient getNewClient() {
        return new ElideDataStorePassClient(refreshableElide);
    }

    /**
     * This is the only method that the Servlet calls - it orchestrates the process of building
     * a Journal object from the supplied JSON object, seeing if the Journal is present in PASS,
     * creating or updating that Journal if needed, and finally returning the PASS id for the Journal
     *
     * @param xrefJsonObject the supplied crossref JSON object
     * @return the id of the corresponding Journal object in PASS
     *
     */
    String resolveJournal(JsonObject xrefJsonObject) {

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

        Journal passJournal = find(name, issns);

        if (passJournal == null) {
            // we don't have this journal in pass yet
            if (name != null && !name.isEmpty() && issns.size() > 0) {
                // we have enough info to make a journal entry
                // passJournal = passClient.createAndReadResource(journal, Journal.class);
            } else {
                // do not have enough to create a new journal
                LOG.debug("Not enough info for journal " + name);
                return null;
            }
        } else { //we have a journal, let's see if we can add anything new - just issns atm. we add only if not present
            //passJournal = passClient.readResource(passJournalUri, Journal.class);

            if (passJournal != null) {
                //check to see if we can supply issns
                if (!passJournal.getIssns().containsAll(journal.getIssns())) {
                    List<String> newIssnList = Stream.concat(passJournal.getIssns().stream(),
                                                             journal.getIssns().stream()).distinct()
                                                     .collect(Collectors.toList());
                    passJournal.setIssns(newIssnList);
                    try (ElideDataStorePassClient passClient = getNewClient()) {
                        passClient.updateObject(passJournal);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                } else {
                    String uhoh = "Journal " + passJournal.getJournalName() +
                                  " was found, but the object could not be " +
                                  "retrieved. This should never happen.";
                    LOG.error(uhoh);
                    throw new RuntimeException(uhoh);
                }
            }
        }

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
    Journal find(String name, List<String> issns) {

        //keep track of hits for searches
        List<Journal> foundList = new ArrayList<>();

        //look for journals with this name
        try (ElideDataStorePassClient passClient = getNewClient()) {
            String filter = RSQL.equals("name", name);
            PassClientResult<Journal> result = passClient.
                selectObjects(new PassClientSelector<Journal>(Journal.class, 0, 100, filter, null));
            result.getObjects().forEach(j -> {
                foundList.add(j);
            });
        } catch (Exception e) {
            e.printStackTrace();
        }

        //look for journals with any of these issns
        if (!issns.isEmpty()) {
            for (String issn : issns) {
                try (ElideDataStorePassClient passClient = getNewClient()) {
                    String filter = RSQL.equals("issns", issn);
                    PassClientResult<Journal> result = passClient.
                        selectObjects(new PassClientSelector<>(Journal.class, 0, 100, filter, null));
                    result.getObjects().forEach(j -> {
                        foundList.add(j);
                    });
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        //count the number of hits for each Journal
        if (foundList.size() == 0) {
            return null;
        } else {
            Map<Journal, Long> scoreMap = foundList.stream()
                                                   .collect(Collectors.groupingBy(e -> e, Collectors.counting()));

            // we have matches, pick the best one
            Long highScore = Collections.max(scoreMap.values());

            for (Journal journal : scoreMap.keySet()) {
                if (scoreMap.get(journal).equals(highScore)) {
                    return journal;
                }
            }
        }
        return null; //never reached
    }

}