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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.StringReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;

import org.dataconservancy.pass.model.Journal;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for the doi service
 */
public class PassDoiServletTest {

    private final PassDoiServlet underTest = new PassDoiServlet();

    private final String issn1 = String.join(":", PassDoiServlet.IssnType.PRINT.getPassTypeString(), "0000-0001");
    private final String issn2 = String.join(":", PassDoiServlet.IssnType.ELECTRONIC.getPassTypeString(), "0000-0002");
    private final String issn3 = String.join(":", PassDoiServlet.IssnType.ELECTRONIC.getPassTypeString(), "0000-0003");
    private final String issn4 = String.join(":", PassDoiServlet.IssnType.ELECTRONIC.getPassTypeString(), "0000-0004");
    private final String issn5 = String.join(":", PassDoiServlet.IssnType.ELECTRONIC.getPassTypeString(), "0000-0005");
    private final String issn6 = String.join(":", PassDoiServlet.IssnType.ELECTRONIC.getPassTypeString(), "0000-0006");

    private final URI completeId = URI.create("http://example.org:2020/" + UUID.randomUUID().toString());
    private final URI missingNameId = URI.create("http://example.org:2020/" + UUID.randomUUID().toString());
    private final URI missingOneIssnId = URI.create("http://example.org:2020/" + UUID.randomUUID().toString());

    //a real-life JSON metadata response for a DOI, from Crossref
    private final String xrefJson = "{\"status\":\"ok\",\"message-type\":\"work\",\"message-version\":\"1.0.0\"," +
                                    "\"message\":" +
                                    "{\"indexed\":{\"date-parts\":[[2018,9,11]]," +
                                    "\"date-time\":\"2018-09-11T22:02:39Z\"," +
                                    "\"timestamp\":" +
                                    "1536703359538},\"reference-count\":74,\"publisher\":\"SAGE Publications\"," +
                                    "\"license\":[{\"URL\":" +
                                    "\"http:\\/\\/journals.sagepub.com\\/page\\/policies\\/text-and-data-mining" +
                                    "-license\"," +
                                    "\"start\":" +
                                    "{\"date-parts\":[[2016,1,1]],\"date-time\":\"2016-01-01T00:00:00Z\"," +
                                    "\"timestamp\":" +
                                    "1451606400000},\"delay-in-days\":0,\"content-version\":\"tdm\"}]," +
                                    "\"content-domain\":{\"domain\":" +
                                    "[\"journals.sagepub.com\"],\"crossmark-restriction\":true}," +
                                    "\"short-container-title\":" +
                                    "[\"Clinical Medicine Insights: Cardiology\"]," +
                                    "\"published-print\":{\"date-parts\":" +
                                    "[[2016,1]]},\"DOI\":\"10.4137\\/cmc.s38446\",\"type\":\"journal-article\"," +
                                    "\"created\":" +
                                    "{\"date-parts\":[[2016,10,19]],\"date-time\":\"2016-10-19T21:18:54Z\"," +
                                    "\"timestamp\":" +
                                    "1476911934000},\"page\":\"CMC.S38446\",\"update-policy\":" +
                                    "\"http:\\/\\/dx.doi.org\\/10.1177\\/sage-journals-update-policy\",\"source\":" +
                                    "\"Crossref\",\"is-referenced-by-count\":1,\"title\":" +
                                    "[\"Arrhythmogenic Right Ventricular Dysplasia in Neuromuscular Disorders\"]," +
                                    "\"prefix\":" +
                                    "\"10.4137\",\"volume\":\"10\",\"author\":[{\"given\":\"Josef\"," +
                                    "\"family\":\"Finsterer\",\"sequence\":" +
                                    "\"first\",\"affiliation\":[{\"name\":\"Krankenanstalt Rudolfstiftung, Vienna, " +
                                    "Austria" +
                                    ".\"}]},{\"given\":" +
                                    "\"Claudia\",\"family\":\"St\\u00f6llberger\",\"sequence\":\"additional\"," +
                                    "\"affiliation\":[{\"name\":" +
                                    "\"Krankenanstalt Rudolfstiftung, Vienna, Austria.\"}]}],\"member\":\"179\"," +
                                    "\"published-online\":" +
                                    "{\"date-parts\":[[2016,10,19]]},\"container-title\":[\"Clinical Medicine " +
                                    "Insights: " +
                                    "Cardiology\"],\"original-title\":" +
                                    "[],\"language\":\"en\",\"link\":[{\"URL\":\"http:\\/\\/journals.sagepub" +
                                    ".com\\/doi\\/pdf\\/10.4137\\/CMC.S38446\",\"content-type\":" +
                                    "\"application\\/pdf\",\"content-version\":\"vor\"," +
                                    "\"intended-application\":\"text-mining\"},{\"URL\":" +
                                    "\"http:\\/\\/journals.sagepub.com\\/doi\\/full-xml\\/10.4137\\/CMC.S38446\"," +
                                    "\"content-type\":\"application\\/xml\",\"content-version\":" +
                                    "\"vor\",\"intended-application\":\"text-mining\"},{\"URL\":" +
                                    "\"http:\\/\\/journals.sagepub.com\\/doi\\/pdf\\/10.4137\\/CMC.S38446\"," +
                                    "\"content-type\":\"unspecified\",\"content-version\":" +
                                    "\"vor\",\"intended-application\":\"similarity-checking\"}]," +
                                    "\"deposited\":{\"date-parts\":[[2017,12,13]],\"date-time\":" +
                                    "\"2017-12-13T00:51:44Z\",\"timestamp\":1513126304000},\"score\":1.0," +
                                    "\"subtitle\":[]," +
                                    "\"short-title\":[],\"issued\":" +
                                    "{\"date-parts\":[[2016,1]]},\"references-count\":74,\"alternative-id\":[\"10" +
                                    ".4137\\/CMC.S38446\"],\"URL\":" +
                                    "\"http:\\/\\/dx.doi.org\\/10.4137\\/cmc.s38446\",\"relation\":{}," +
                                    "\"ISSN\":[\"1179-5468\",\"1179-5468\"],\"issn-type\":[{\"value\":" +
                                    "\"1179-5468\",\"type\":\"print\"},{\"value\":\"1179-5468\"," +
                                    "\"type\":\"electronic\"}]}}";

    /**
     * set up stuff, including a lot of mocks
     *
     * @throws Exception if something goes wrong
     */
    @Before
    public void setUp() throws Exception {

        List<String> issnListComplete = new ArrayList<>();
        issnListComplete.add(issn1);
        issnListComplete.add(issn2);

        List<String> issnListMissingName = new ArrayList<>();
        issnListMissingName.add(issn3);
        issnListMissingName.add(issn4);

        List<String> issnListOneIssn = new ArrayList<>();
        issnListOneIssn.add(issn5);

        Journal completeJournal = new Journal();
        completeJournal.setId(completeId);
        String journalName = "Fancy Journal";
        completeJournal.setJournalName(journalName);

        String nlmta = "Irrelevant Data Item";
        completeJournal.setNlmta(nlmta);
        completeJournal.setIssns(issnListComplete);

        Journal missingNameJournal = new Journal();
        missingNameJournal.setId(missingNameId);
        missingNameJournal.setNlmta(nlmta);
        missingNameJournal.setIssns(issnListMissingName);

        Journal missingOneIssnJournal = new Journal();
        missingOneIssnJournal.setId(missingOneIssnId);
        missingOneIssnJournal.setNlmta(nlmta);
        missingOneIssnJournal.setJournalName(journalName);
        missingOneIssnJournal.setIssns(issnListOneIssn);

        underTest.init(null);
    }

    /**
     * test that hitting the Crossref API with a doi returns the expected JSON object
     */
    @Test
    public void testXrefLookup() {
        String realDoi = "10.4137/cmc.s38446";
        JsonObject blob = underTest.retrieveXrefMetdata(realDoi);
        //these results will differ by a timestamp - but a good check is that they return the same journal objects
        JsonReader reader = Json.createReader(new StringReader(xrefJson));
        JsonObject object = reader.readObject();
        reader.close();

        assertNotNull(blob.getJsonObject("message").getJsonArray("ISSN"));
        assertEquals(blob.getJsonObject("message").getJsonArray("ISSN"),
                     object.getJsonObject("message").getJsonArray("ISSN"));
    }

    /**
     * test that a bad doi gives the required error message
     */
    @Test
    public void testBadDoiLookup() {
        String badDoi = "10.1212/abc.DEF";
        JsonObject blob = underTest.retrieveXrefMetdata(badDoi);
        assertEquals("Resource not found.", blob.getString("error"));
    }

    /**
     * Test that our verify method correctly handle the usual expected doi formats
     */
    @Test
    public void verifyTest() {
        String doi0 = "http://dx.doi.org/10.4137/cmc.s38446";
        assertEquals("10.4137/cmc.s38446", underTest.verify(doi0));

        String doi1 = "https://dx.doi.org/10.4137/cmc.s38446";
        assertEquals("10.4137/cmc.s38446", underTest.verify(doi1));

        String doi2 = "dx.doi.org/10.4137/cmc.s38446";
        assertEquals("10.4137/cmc.s38446", underTest.verify(doi2));

        String doi3 = "10.4137/cmc.s38446";
        assertEquals("10.4137/cmc.s38446", underTest.verify(doi3));

        String doi4 = "4137/cmc.s38446";
        assertNull(underTest.verify(doi4));
    }
}

