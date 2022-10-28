package org.dataconservancy.pass.doi.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.dataconservancy.pass.client.PassClient;
import org.dataconservancy.pass.model.Journal;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class FedoraConnectorTest {

    private final URI newJournalId = URI.create("newlyCreatedId");
    private final String issn1 = String.join(":", PassDoiServlet.IssnType.PRINT.getPassTypeString(), "0000-0001");
    private final String issn2 = String.join(":", PassDoiServlet.IssnType.ELECTRONIC.getPassTypeString(), "0000-0002");
    private final String issn3 = String.join(":", PassDoiServlet.IssnType.ELECTRONIC.getPassTypeString(), "0000-0003");
    private final String issn4 = String.join(":", PassDoiServlet.IssnType.ELECTRONIC.getPassTypeString(), "0000-0004");
    private final String issn5 = String.join(":", PassDoiServlet.IssnType.ELECTRONIC.getPassTypeString(), "0000-0005");
    private final String issn6 = String.join(":", PassDoiServlet.IssnType.ELECTRONIC.getPassTypeString(), "0000-0006");
    private final URI completeId = URI.create("http://example.org:2020/" + UUID.randomUUID());
    private final URI missingNameId = URI.create("http://example.org:2020/" + UUID.randomUUID());
    private final URI missingOneIssnId = URI.create("http://example.org:2020/" + UUID.randomUUID());
    private final String nlmta = "Irrelevant Data Item";
    private final String journalName = "Fancy Journal";
    @Mock
    PassClient passClientMock;
    private FedoraConnector underTest;
    private Journal completeJournal;

    /**
     * set up stuff, including a lot of mocks
     */
    @Before
    public void setUp() {
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

        Journal missingNameJournal = new Journal();
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

        underTest = new FedoraConnector();
        underTest.passClient = passClientMock;
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
}
