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
package org.dataconservancy.pass.doi.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.io.StringReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;

import org.dataconservancy.pass.client.PassClient;
import org.dataconservancy.pass.client.PassJsonAdapter;
import org.dataconservancy.pass.client.adapter.PassJsonAdapterBasic;
import org.dataconservancy.pass.model.Journal;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * Unit tests for the doi service
 */
@RunWith(MockitoJUnitRunner.class)
public class PassDoiServiceTest {

    @Mock
    PassClient passClientMock;

    private PassDoiServlet underTest;

    private PassJsonAdapter json = new PassJsonAdapterBasic();

    private URI newJournalId = URI.create("newlyCreatedId");

    private Journal completeJournal;
    private Journal missingNameJournal;

    private String issn1 = String.join(":", PassDoiServlet.IssnType.PRINT.getPassTypeString(), "0000-0001");
    private String issn2 = String.join(":", PassDoiServlet.IssnType.ELECTRONIC.getPassTypeString(), "0000-0002");

    private String issn3 = String.join(":", PassDoiServlet.IssnType.ELECTRONIC.getPassTypeString(), "0000-0003");
    private String issn4 = String.join(":", PassDoiServlet.IssnType.ELECTRONIC.getPassTypeString(), "0000-0004");


    private String issn5 = String.join(":", PassDoiServlet.IssnType.ELECTRONIC.getPassTypeString(), "0000-0005");
    private String issn6 = String.join(":", PassDoiServlet.IssnType.ELECTRONIC.getPassTypeString(), "0000-0006");

    private URI completeId = URI.create("http://example.org:2020/" + UUID.randomUUID().toString());
    private URI missingNameId = URI.create("http://example.org:2020/" + UUID.randomUUID().toString());
    private URI missingOneIssnId = URI.create("http://example.org:2020/" + UUID.randomUUID().toString());

    private String nlmta = "Irrelevant Data Item";
    private String journalName = "Fancy Journal";

    //a real-life JSON metadata response for a DOI, from Crossref
    private String xrefJson = "{\"status\":\"ok\",\"message-type\":\"work\",\"message-version\":\"1.0.0\"," +
                              "\"message\":" +
                              "{\"indexed\":{\"date-parts\":[[2018,9,11]],\"date-time\":\"2018-09-11T22:02:39Z\"," +
                              "\"timestamp\":" +
                              "1536703359538},\"reference-count\":74,\"publisher\":\"SAGE Publications\"," +
                              "\"license\":[{\"URL\":" +
                              "\"http:\\/\\/journals.sagepub.com\\/page\\/policies\\/text-and-data-mining-license\"," +
                              "\"start\":" +
                              "{\"date-parts\":[[2016,1,1]],\"date-time\":\"2016-01-01T00:00:00Z\",\"timestamp\":" +
                              "1451606400000},\"delay-in-days\":0,\"content-version\":\"tdm\"}]," +
                              "\"content-domain\":{\"domain\":" +
                              "[\"journals.sagepub.com\"],\"crossmark-restriction\":true},\"short-container-title\":" +
                              "[\"Clinical Medicine Insights: Cardiology\"],\"published-print\":{\"date-parts\":" +
                              "[[2016,1]]},\"DOI\":\"10.4137\\/cmc.s38446\",\"type\":\"journal-article\",\"created\":" +
                              "{\"date-parts\":[[2016,10,19]],\"date-time\":\"2016-10-19T21:18:54Z\",\"timestamp\":" +
                              "1476911934000},\"page\":\"CMC.S38446\",\"update-policy\":" +
                              "\"http:\\/\\/dx.doi.org\\/10.1177\\/sage-journals-update-policy\",\"source\":" +
                              "\"Crossref\",\"is-referenced-by-count\":1,\"title\":" +
                              "[\"Arrhythmogenic Right Ventricular Dysplasia in Neuromuscular Disorders\"]," +
                              "\"prefix\":" +
                              "\"10.4137\",\"volume\":\"10\",\"author\":[{\"given\":\"Josef\"," +
                              "\"family\":\"Finsterer\",\"sequence\":" +
                              "\"first\",\"affiliation\":[{\"name\":\"Krankenanstalt Rudolfstiftung, Vienna, Austria" +
                              ".\"}]},{\"given\":" +
                              "\"Claudia\",\"family\":\"St\\u00f6llberger\",\"sequence\":\"additional\"," +
                              "\"affiliation\":[{\"name\":" +
                              "\"Krankenanstalt Rudolfstiftung, Vienna, Austria.\"}]}],\"member\":\"179\"," +
                              "\"published-online\":" +
                              "{\"date-parts\":[[2016,10,19]]},\"container-title\":[\"Clinical Medicine Insights: " +
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
                              "\"2017-12-13T00:51:44Z\",\"timestamp\":1513126304000},\"score\":1.0,\"subtitle\":[]," +
                              "\"short-title\":[],\"issued\":" +
                              "{\"date-parts\":[[2016,1]]},\"references-count\":74,\"alternative-id\":[\"10" +
                              ".4137\\/CMC.S38446\"],\"URL\":" +
                              "\"http:\\/\\/dx.doi.org\\/10.4137\\/cmc.s38446\",\"relation\":{}," +
                              "\"ISSN\":[\"1179-5468\",\"1179-5468\"],\"issn-type\":[{\"value\":" +
                              "\"1179-5468\",\"type\":\"print\"},{\"value\":\"1179-5468\",\"type\":\"electronic\"}]}}";

    private String xrefJsonExtraIssn = "{\"status\":\"ok\",\"message-type\":\"work\",\"message-version\":\"1.0.0\"," +
                                       "\"message\":" +
                                       "{\"indexed\":{\"date-parts\":[[2018,9,11]]," +
                                       "\"date-time\":\"2018-09-11T22:02:39Z\",\"timestamp\":" +
                                       "1536703359538},\"reference-count\":74,\"publisher\":\"SAGE Publications\"," +
                                       "\"license\":[{\"URL\":" +
                                       "\"http:\\/\\/journals.sagepub.com\\/page\\/policies\\/text-and-data-mining" +
                                       "-license\",\"start\":" +
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
                                       "\"first\",\"affiliation\":[{\"name\":\"Krankenanstalt Rudolfstiftung, Vienna," +
                                       " Austria.\"}]},{\"given\":" +
                                       "\"Claudia\",\"family\":\"St\\u00f6llberger\",\"sequence\":\"additional\"," +
                                       "\"affiliation\":[{\"name\":" +
                                       "\"Krankenanstalt Rudolfstiftung, Vienna, Austria.\"}]}],\"member\":\"179\"," +
                                       "\"published-online\":" +
                                       "{\"date-parts\":[[2016,10,19]]},\"container-title\":[\"Clinical Medicine " +
                                       "Insights: Cardiology\"],\"original-title\":" +
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
                                       "\"subtitle\":[],\"short-title\":[],\"issued\":" +
                                       "{\"date-parts\":[[2016,1]]},\"references-count\":74,\"alternative-id\":[\"10" +
                                       ".4137\\/CMC.S38446\"],\"URL\":" +
                                       "\"http:\\/\\/dx.doi.org\\/10.4137\\/cmc.s38446\",\"relation\":{}," +
                                       "\"ISSN\":[\"1179-5468\",\"1179-5468\", \"1234-5678\"]," +
                                       "\"issn-type\":[{\"value\":" +
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

        completeJournal = new Journal();
        completeJournal.setId(completeId);
        completeJournal.setJournalName(journalName);

        completeJournal.setNlmta(nlmta);
        completeJournal.setIssns(issnListComplete);

        missingNameJournal = new Journal();
        missingNameJournal.setId(missingNameId);
        missingNameJournal.setNlmta(nlmta);
        missingNameJournal.setIssns(issnListMissingName);

        Journal missingOneIssnJournal = new Journal();
        missingOneIssnJournal.setId(missingOneIssnId);
        missingOneIssnJournal.setNlmta(nlmta);
        missingOneIssnJournal.setJournalName(journalName);
        missingOneIssnJournal.setIssns(issnListOneIssn);

        when(passClientMock.createAndReadResource(any(), eq(Journal.class))).thenAnswer(i -> {
            final Journal givenJournalToCreate = i.getArgument(0);
            givenJournalToCreate.setId(newJournalId);
            return givenJournalToCreate;
        });

        when(passClientMock.findAllByAttribute(Journal.class, "issns", issn1)).thenReturn(
            new HashSet<>(Collections.singleton(completeId)));
        when(passClientMock.findAllByAttribute(Journal.class, "issns", issn2)).thenReturn(
            new HashSet<>(Collections.singleton(completeId)));
        when(passClientMock.findAllByAttribute(Journal.class, "issns", issn4)).thenReturn(
            new HashSet<>(Collections.singleton(missingNameId)));
        when(passClientMock.findAllByAttribute(Journal.class, "issns", issn5)).thenReturn(
            new HashSet<>(Collections.singleton(missingOneIssnId)));

        when(passClientMock.readResource(completeId, Journal.class)).thenReturn(completeJournal);
        when(passClientMock.readResource(missingNameId, Journal.class)).thenReturn(missingNameJournal);
        when(passClientMock.readResource(missingOneIssnId, Journal.class)).thenReturn(missingOneIssnJournal);

        underTest = new PassDoiServlet();
        underTest.passClient = passClientMock;
        underTest.json = json;
        underTest.init(null);
    }


    /**
     * We test that JSON metadata for a journal article populates a PASS Journal object as expected
     */
    @Test
    public void buildPassJournalTest() {
        JsonReader reader = Json.createReader(new StringReader(xrefJson));
        JsonObject object = reader.readObject();
        reader.close();
        Journal passJournal = underTest.buildPassJournal(object);

        assertEquals("Clinical Medicine Insights: Cardiology", passJournal.getJournalName());
        assertEquals(2, passJournal.getIssns().size());
        assertTrue(passJournal.getIssns().contains("Print:1179-5468"));
        assertTrue(passJournal.getIssns().contains("Online:1179-5468"));
        assertFalse(passJournal.getIssns().contains(":1234-5678"));

    }

    /**
     * We test that JSON metadata for a journal article populates a PASS Journal object as expected
     */
    @Test
    public void buildPassJournalExtraIssnTest() {
        JsonReader reader = Json.createReader(new StringReader(xrefJsonExtraIssn));
        JsonObject object = reader.readObject();
        reader.close();
        Journal passJournal = underTest.buildPassJournal(object);

        assertEquals("Clinical Medicine Insights: Cardiology", passJournal.getJournalName());
        assertEquals(3, passJournal.getIssns().size());
        assertTrue(passJournal.getIssns().contains("Print:1179-5468"));
        assertTrue(passJournal.getIssns().contains("Online:1179-5468"));
        assertTrue(passJournal.getIssns().contains(":1234-5678"));

    }


    /**
     * we test the update method to make sure journals with various characteristics behave as expected
     */
    @Test
    public void updateJournalInPassTest() {

        //first test that if a journal is not found, that a new one is created:
        Journal xrefJournal = new Journal();
        xrefJournal.getIssns().add("MOO");
        xrefJournal.setJournalName("Advanced Research in Animal Husbandry");

        Journal newJournal = underTest.updateJournalInPass(xrefJournal);

        assertEquals(xrefJournal.getIssns(), newJournal.getIssns());
        assertEquals(xrefJournal.getJournalName(), newJournal.getJournalName());

        //test that a journal not needing an update does not change in PASS
        xrefJournal = new Journal();
        xrefJournal.getIssns().add(issn1);
        xrefJournal.getIssns().add(issn2);
        xrefJournal.setJournalName(journalName);

        newJournal = underTest.updateJournalInPass(xrefJournal);
        assertEquals(completeJournal.getJournalName(), newJournal.getJournalName());
        assertEquals(completeJournal.getIssns(), newJournal.getIssns());
        assertEquals(completeJournal.getNlmta(), newJournal.getNlmta());

        //test that an overwrite does not happen if a name or nlmta value in the xref journal
        //is different from the pass journal (also check that we can find the journal in PASS
        //from its second issn)
        xrefJournal = new Journal();
        xrefJournal.getIssns().add(issn2);
        xrefJournal.setJournalName("Advanced Research in Animal Husbandry");

        newJournal = underTest.updateJournalInPass(xrefJournal);
        assertEquals(completeJournal.getId(), newJournal.getId());
        assertEquals(completeJournal.getJournalName(), newJournal.getJournalName());
        assertEquals(2, completeJournal.getIssns().size());
        assertTrue(completeJournal.getIssns().contains(issn1));
        assertTrue(completeJournal.getIssns().contains(issn1));
        assertEquals(completeJournal.getNlmta(), newJournal.getNlmta());

        //test that a Pass journal with only one issn will have a second one added if the xref journal has two
        xrefJournal = new Journal();
        xrefJournal.getIssns().add(issn5);
        xrefJournal.getIssns().add(issn6);

        newJournal = underTest.updateJournalInPass(xrefJournal);//issn5 belongs to the Journal with only one issn
        assertEquals(2, xrefJournal.getIssns().size());
        assertEquals(2, newJournal.getIssns().size());
        assertEquals(nlmta, newJournal.getNlmta());

        //test that an xref journal with only one issn will find its match in a pass journal containing two issns
        xrefJournal = new Journal();
        xrefJournal.getIssns().add(issn4);
        xrefJournal.setJournalName("Advanced Research in Animal Husbandry");

        newJournal = underTest.updateJournalInPass(xrefJournal);
        assertEquals(2, newJournal.getIssns().size());
        assertEquals(nlmta, newJournal.getNlmta());
    }

    /**
     * Test that the find() method returns the urI best matching the supplied arguments
     */
    @Test
    public void resultSortWorksCorrectlyTest() {
        when(passClientMock.findAllByAttribute(Journal.class, "issns", issn1)).thenReturn(
            new HashSet<>(Collections.singleton(completeId)));
        when(passClientMock.findAllByAttribute(Journal.class, "issns", issn2)).thenReturn(
            new HashSet<>(Collections.singletonList(missingNameId)));
        when(passClientMock.findAllByAttribute(Journal.class, "name", journalName)).thenReturn(
            new HashSet<>(Arrays.asList(completeId, missingNameId)));

        URI resultUri = underTest.find(journalName, Collections.singletonList(issn1));
        assertEquals(completeId, resultUri);

        resultUri = underTest.find(journalName, Collections.singletonList(issn2));
        assertEquals(missingNameId, resultUri);

        resultUri = underTest.find("MOO", Collections.singletonList(issn2));
        assertEquals(missingNameId, resultUri);

        resultUri = underTest.find("MOO", Collections.singletonList(issn1));
        assertEquals(completeId, resultUri);

        resultUri = underTest.find("MOO", Arrays.asList(issn1, issn2));
        assertNotNull(resultUri);
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

