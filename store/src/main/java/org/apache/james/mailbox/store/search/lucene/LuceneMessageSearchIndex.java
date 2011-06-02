/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/
package org.apache.james.mailbox.store.search.lucene;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.mail.Flags;
import javax.mail.Flags.Flag;

import org.apache.commons.lang.time.DateUtils;
import org.apache.james.mailbox.MailboxException;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.mailbox.MessageRange;
import org.apache.james.mailbox.SearchQuery;
import org.apache.james.mailbox.SearchQuery.Conjunction;
import org.apache.james.mailbox.UnsupportedSearchException;
import org.apache.james.mailbox.SearchQuery.AllCriterion;
import org.apache.james.mailbox.SearchQuery.ContainsOperator;
import org.apache.james.mailbox.SearchQuery.Criterion;
import org.apache.james.mailbox.SearchQuery.DateOperator;
import org.apache.james.mailbox.SearchQuery.DateResolution;
import org.apache.james.mailbox.SearchQuery.FlagCriterion;
import org.apache.james.mailbox.SearchQuery.HeaderCriterion;
import org.apache.james.mailbox.SearchQuery.HeaderOperator;
import org.apache.james.mailbox.SearchQuery.NumericOperator;
import org.apache.james.mailbox.SearchQuery.NumericRange;
import org.apache.james.mailbox.SearchQuery.UidCriterion;
import org.apache.james.mailbox.store.mail.model.Mailbox;
import org.apache.james.mailbox.store.mail.model.Message;
import org.apache.james.mailbox.store.search.MessageSearchIndex;
import org.apache.james.mime4j.MimeException;
import org.apache.james.mime4j.descriptor.BodyDescriptor;
import org.apache.james.mime4j.field.AddressListField;
import org.apache.james.mime4j.field.address.Address;
import org.apache.james.mime4j.field.address.AddressList;
import org.apache.james.mime4j.field.address.Group;
import org.apache.james.mime4j.field.address.MailboxList;
import org.apache.james.mime4j.message.Header;
import org.apache.james.mime4j.message.SimpleContentHandler;
import org.apache.james.mime4j.parser.MimeEntityConfig;
import org.apache.james.mime4j.parser.MimeStreamParser;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.NumericField;
import org.apache.lucene.document.Field.Index;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.NumericRangeQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.WildcardQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.LockObtainFailedException;
import org.apache.lucene.util.Version;

/**
 * Lucene based {@link MessageSearchIndex} which offers message searching via a Lucene index
 * 
 * 

 * @param <Id>
 */
public class LuceneMessageSearchIndex<Id> implements MessageSearchIndex<Id>{

    /**
     * Default max query results
     */
    public final static int DEFAULT_MAX_QUERY_RESULTS = 100000;
    
    /**
     * {@link Field} which will contain the unique index of the {@link Document}
     */
    public final static String ID_FIELD ="id";
    
    
    /**
     * {@link Field} which will contain uid of the {@link MailboxMembership}
     */
    public final static String UID_FIELD = "uid";
    
    /**
     * {@link Field} which will contain the {@link Flags} of the {@link MailboxMembership}
     */
    public final static String FLAGS_FIELD = "flags";
  
    /**
     * {@link Field} which will contain the size of the {@link MailboxMembership}
     */
    public final static String SIZE_FIELD = "size";

    /**
     * {@link Field} which will contain the body of the {@link MailboxMembership}
     */
    public final static String BODY_FIELD = "body";
    
    
    /**
     * Prefix which will be used for each message header to store it also in a seperate {@link Field}
     */
    public final static String PREFIX_HEADER_FIELD ="header_";
    
    /**
     * {@link Field} which will contain the whole message header of the {@link MailboxMembership}
     */
    public final static String HEADERS_FIELD ="headers";

    /**
     * {@link Field} which will contain the mod-sequence of the message
     */
    public final static String MODSEQ_FIELD = "modSeq";

    /**
     * {@link Field} which will contain the TO-Address of the message
     */
    public final static String TO_FIELD ="to";
    

    /**
     * {@link Field} which will contain the CC-Address of the message
     */
    public final static String CC_FIELD ="cc";

    /**
     * {@link Field} which will contain the BCC-Address of the message
     */
    public final static String BCC_FIELD ="bcc";
    

    /**
     * {@link Field} which will contain the FROM-Address of the message
     */
    public final static String FROM_FIELD ="from";
    
    /**
     * {@link Field} which contain the internalDate of the message with YEAR-Resolution
     */
    public final static String INTERNAL_DATE_FIELD_YEAR_RESOLUTION ="internaldateYearResolution";
    
    
    /**
     * {@link Field} which contain the internalDate of the message with MONTH-Resolution
     */
    public final static String INTERNAL_DATE_FIELD_MONTH_RESOLUTION ="internaldateMonthResolution";
    
    /**
     * {@link Field} which contain the internalDate of the message with DAY-Resolution
     */
    public final static String INTERNAL_DATE_FIELD_DAY_RESOLUTION ="internaldateDayResolution";
    
    /**
     * {@link Field} which contain the internalDate of the message with HOUR-Resolution
     */
    public final static String INTERNAL_DATE_FIELD_HOUR_RESOLUTION ="internaldateHourResolution";
    
    /**
     * {@link Field} which contain the internalDate of the message with MINUTE-Resolution
     */
    public final static String INTERNAL_DATE_FIELD_MINUTE_RESOLUTION ="internaldateMinuteResolution";
    
    /**
     * {@link Field} which contain the internalDate of the message with SECOND-Resolution
     */
    public final static String INTERNAL_DATE_FIELD_SECOND_RESOLUTION ="internaldateSecondResolution";
    
    
    /**
     * {@link Field} which contain the internalDate of the message with MILLISECOND-Resolution
     */
    public final static String INTERNAL_DATE_FIELD_MILLISECOND_RESOLUTION ="internaldateMillisecondResolution";

    /**
     * {@link Field} which will contain the id of the {@link Mailbox}
     */
    public final static String MAILBOX_ID_FIELD ="mailboxid";

    
    
    private final static String MEDIA_TYPE_TEXT = "text"; 
    private final static String MEDIA_TYPE_MESSAGE = "message"; 
    private final static String DEFAULT_ENCODING = "US-ASCII";
    
    private final IndexWriter writer;
    
    private int maxQueryResults = DEFAULT_MAX_QUERY_RESULTS;

    private boolean suffixMatch = false;
    
    private final static Sort UID_SORT = new Sort(new SortField(UID_FIELD, SortField.LONG));
    
    public LuceneMessageSearchIndex(Directory directory) throws CorruptIndexException, LockObtainFailedException, IOException {
        this(directory, true);
    }
    
    
    public LuceneMessageSearchIndex(Directory directory, boolean lenient) throws CorruptIndexException, LockObtainFailedException, IOException {
        this(new IndexWriter(directory,  new IndexWriterConfig(Version.LUCENE_31, createAnalyzer(lenient))));
    }
    
    
    public LuceneMessageSearchIndex(IndexWriter writer) {
        this.writer = writer;
    }
    
    /**
     * Set the max count of results which will get returned from a query. The default is {@link #DEFAULT_MAX_QUERY_RESULTS}
     * 
     * @param maxQueryResults
     */
    public void setMaxQueryResults(int maxQueryResults) {
        this.maxQueryResults = maxQueryResults;
    }
    /**
     * Create a {@link Analyzer} which is used to index the {@link MailboxMembership}'s
     * 
     * @param lenient 
     * 
     * @return analyzer
     */
    private static Analyzer createAnalyzer(boolean lenient) {
        if (lenient) {
           return new LenientImapSearchAnalyzer();
        } else {
            return new StrictImapSearchAnalyzer();
        }
    }
    
    /**
     * If set to true this implementation will use {@link WildcardQuery} to match suffix and prefix. This is what RFC3501 expects but is often not what the user does.
     * It also slow things a lot if you have complex queries which use many "TEXT" arguments. If you want the implementation to behave strict like RFC3501 says, you should
     * set this to true. 
     * 
     * The default is false for performance reasons
     * 
     * 
     * @param suffixMatch
     */
    public void setEnableSuffixMatch(boolean suffixMatch) {
        this.suffixMatch = suffixMatch;
    }
    
    
    
    /*
     * (non-Javadoc)
     * @see org.apache.james.mailbox.store.MessageSearchIndex#search(org.apache.james.mailbox.MailboxSession, org.apache.james.mailbox.store.mail.model.Mailbox, org.apache.james.mailbox.SearchQuery)
     */
    public Iterator<Long> search(MailboxSession session, Mailbox<Id> mailbox, SearchQuery searchQuery) throws MailboxException {
        Set<Long> uids = new HashSet<Long>();
        IndexSearcher searcher = null;

        try {
            searcher = new IndexSearcher(IndexReader.open(writer, true));
            BooleanQuery query = new BooleanQuery();
            query.add(new TermQuery(new Term(MAILBOX_ID_FIELD, mailbox.getMailboxId().toString())), BooleanClause.Occur.MUST);

            List<Criterion> crits = searchQuery.getCriterias();
            for (int i = 0; i < crits.size(); i++) {
                query.add(createQuery(crits.get(i), mailbox), BooleanClause.Occur.MUST);
            }
                      
            // query for all the documents sorted by uid
            TopDocs docs = searcher.search(query, null, maxQueryResults, UID_SORT);
            ScoreDoc[] sDocs = docs.scoreDocs;
            for (int i = 0; i < sDocs.length; i++) {
                long uid = Long.valueOf(searcher.doc(sDocs[i].doc).get(UID_FIELD));
                uids.add(uid);
            }
        } catch (IOException e) {
            throw new MailboxException("Unable to search the mailbox", e);
        } finally {
            if (searcher != null) {
                try {
                    searcher.close();
                } catch (IOException e) {
                    // ignore on close
                }
            }
        }
        return uids.iterator();
    }

   
    /**
     * Create a new {@link Document} for the given {@link MailboxMembership}. This Document does not contain any flags data. The {@link Flags} are stored in a seperate Document. 
     * 
     * See {@link #createFlagsDocument(Message)}
     * 
     * @param membership
     * @return document
     */
    private Document createMessageDocument(Message<?> membership) throws MailboxException{
        final Document doc = new Document();
        // TODO: Better handling
        doc.add(new Field(MAILBOX_ID_FIELD, membership.getMailboxId().toString().toLowerCase(Locale.US), Store.YES, Index.NOT_ANALYZED));
        doc.add(new NumericField(UID_FIELD,Store.YES, true).setLongValue(membership.getUid()));
        
        // create an unqiue key for the document which can be used later on updates to find the document
        doc.add(new Field(ID_FIELD, membership.getMailboxId().toString().toLowerCase(Locale.US) +"-" + Long.toString(membership.getUid()), Store.YES, Index.NOT_ANALYZED));

        doc.add(new NumericField(INTERNAL_DATE_FIELD_YEAR_RESOLUTION,Store.NO, true).setLongValue(DateUtils.truncate(membership.getInternalDate(),Calendar.YEAR).getTime()));
        doc.add(new NumericField(INTERNAL_DATE_FIELD_MONTH_RESOLUTION,Store.NO, true).setLongValue(DateUtils.truncate(membership.getInternalDate(),Calendar.MONTH).getTime()));
        doc.add(new NumericField(INTERNAL_DATE_FIELD_DAY_RESOLUTION,Store.NO, true).setLongValue(DateUtils.truncate(membership.getInternalDate(),Calendar.DAY_OF_MONTH).getTime()));
        doc.add(new NumericField(INTERNAL_DATE_FIELD_HOUR_RESOLUTION,Store.NO, true).setLongValue(DateUtils.truncate(membership.getInternalDate(),Calendar.HOUR_OF_DAY).getTime()));
        doc.add(new NumericField(INTERNAL_DATE_FIELD_MINUTE_RESOLUTION,Store.NO, true).setLongValue(DateUtils.truncate(membership.getInternalDate(),Calendar.MINUTE).getTime()));
        doc.add(new NumericField(INTERNAL_DATE_FIELD_SECOND_RESOLUTION,Store.NO, true).setLongValue(DateUtils.truncate(membership.getInternalDate(),Calendar.SECOND).getTime()));
        doc.add(new NumericField(INTERNAL_DATE_FIELD_MILLISECOND_RESOLUTION,Store.NO, true).setLongValue(DateUtils.truncate(membership.getInternalDate(),Calendar.MILLISECOND).getTime()));

        doc.add(new NumericField(SIZE_FIELD,Store.NO, true).setLongValue(membership.getFullContentOctets()));

        // content handler which will index the headers and the body of the message
        SimpleContentHandler handler = new SimpleContentHandler() {
            

            public void headers(Header header) {
                
                Iterator<org.apache.james.mime4j.parser.Field> fields = header.iterator();
                while(fields.hasNext()) {
                    org.apache.james.mime4j.parser.Field f = fields.next();
                    String headerName = f.getName().toLowerCase(Locale.US);
                    String fullValue =  f.toString().toLowerCase(Locale.US);
                    doc.add(new Field(HEADERS_FIELD, fullValue, Store.NO, Index.ANALYZED));
                    doc.add(new Field(PREFIX_HEADER_FIELD + headerName, f.getBody().toLowerCase(Locale.US) ,Store.NO, Index.ANALYZED));
                    
                    if (f instanceof AddressListField) {
                        AddressListField addressField = (AddressListField) f;
                        String field = null;;
                        if ("To".equalsIgnoreCase(headerName)) {
                            field = TO_FIELD;
                        } else if ("From".equalsIgnoreCase(headerName)) {
                            field = FROM_FIELD;
                        } else if ("Bcc".equalsIgnoreCase(headerName)) {
                            field = BCC_FIELD;
                        } else if ("Cc".equalsIgnoreCase(headerName)) {
                            field = CC_FIELD;
                        }
                        
                        // Check if we can index the the addressfield in the right manner
                        if (field != null) {
                            AddressList aList = addressField.getAddressList();

                            if (aList != null) {
                                for (int i = 0; i < aList.size(); i++) {
                                    Address address = aList.get(i);
                                    if (address instanceof org.apache.james.mime4j.field.address.Mailbox) {
                                        String value = ((org.apache.james.mime4j.field.address.Mailbox) address).getEncodedString().toLowerCase(Locale.US);
                                        doc.add(new Field(field, value, Store.NO, Index.ANALYZED));
                                        
                                    } else if (address instanceof Group) {
                                        MailboxList mList = ((Group) address).getMailboxes();
                                        for (int a = 0; a < mList.size(); a++) {
                                            String value = mList.get(i).getEncodedString().toLowerCase(Locale.US);
                                            doc.add(new Field(field, value, Store.NO, Index.ANALYZED));
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
           
            }
            /*
             * (non-Javadoc)
             * @see org.apache.james.mime4j.message.SimpleContentHandler#bodyDecoded(org.apache.james.mime4j.descriptor.BodyDescriptor, java.io.InputStream)
             */
            public void bodyDecoded(BodyDescriptor desc, InputStream in) throws IOException {
                String mediaType = desc.getMediaType();
                if (MEDIA_TYPE_TEXT.equalsIgnoreCase(mediaType) || MEDIA_TYPE_MESSAGE.equalsIgnoreCase(mediaType)) {
                    String cset = desc.getCharset();
                    if (cset == null) {
                        cset = DEFAULT_ENCODING;
                    }
                    Charset charset;
                    try {
                        charset = Charset.forName(cset);
                    } catch (Exception e) {
                        // Invalid charset found so fallback toe the DEFAULT_ENCODING
                        charset = Charset.forName(DEFAULT_ENCODING);
                    }
                    
                    // Read the content one line after the other and add it to the document
                    BufferedReader bodyReader = new BufferedReader(new InputStreamReader(in, charset));
                    String line = null;
                    while((line = bodyReader.readLine()) != null) {
                        doc.add(new Field(BODY_FIELD,  line.toLowerCase(Locale.US),Store.NO, Index.ANALYZED));
                    }
                    
                }
            }
        };
        MimeEntityConfig config = new MimeEntityConfig();
        config.setMaxLineLen(-1);
        config.setStrictParsing(false);
        config.setMaxContentLen(-1);
        MimeStreamParser parser = new MimeStreamParser(config);
        parser.setContentHandler(handler);
       
        try {
            // parse the message to index headers and body
            parser.parse(membership.getFullContent());
        } catch (MimeException e) {
            // This should never happen as it was parsed before too without problems.            
            throw new MailboxException("Unable to index content of message", e);
        } catch (IOException e) {
            // This should never happen as it was parsed before too without problems.
            // anyway let us just skip the body and headers in the index
            throw new MailboxException("Unable to index content of message", e);
        }
       

        return doc;
    }


    private String toInteralDateField(DateResolution res) {
        String field;
        switch (res) {
        case Year:
            field = INTERNAL_DATE_FIELD_YEAR_RESOLUTION;
            break;
        case Month:
            field = INTERNAL_DATE_FIELD_MONTH_RESOLUTION;
            break;
        case Day:
            field = INTERNAL_DATE_FIELD_DAY_RESOLUTION;
            break;
        case Hour:
            field = INTERNAL_DATE_FIELD_HOUR_RESOLUTION;
            break;
        case Minute:
            field = INTERNAL_DATE_FIELD_MINUTE_RESOLUTION;
            break;
        case Second:
            field = INTERNAL_DATE_FIELD_SECOND_RESOLUTION;
            break;
        default:
            field = INTERNAL_DATE_FIELD_MILLISECOND_RESOLUTION;
            break;
        }
        return field;
    }
    
    /**
     * Return a {@link Query} which is build based on the given {@link SearchQuery.InternalDateCriterion}
     * 
     * @param crit
     * @return query
     * @throws UnsupportedSearchException
     */
    private Query createInternalDateQuery(SearchQuery.InternalDateCriterion crit) throws UnsupportedSearchException {
        DateOperator op = crit.getOperator();
        DateResolution res = op.getDateResultion();
        Date date = op.getDate();
        long value = DateUtils.truncate(date, SearchQuery.toCalendarType(res)).getTime();
        String field = toInteralDateField(res);
        
        switch(op.getType()) {
        case ON:
            return NumericRangeQuery.newLongRange(field ,value, value, true, true);
        case BEFORE: 
            return NumericRangeQuery.newLongRange(field ,0L, value, true, false);
        case AFTER: 
            return NumericRangeQuery.newLongRange(field ,value, Long.MAX_VALUE, false, true);
        default:
            throw new UnsupportedSearchException();
        }
    }
    
    /**
     * Return a {@link Query} which is build based on the given {@link SearchQuery.SizeCriterion}
     * 
     * @param crit
     * @return query
     * @throws UnsupportedSearchException
     */
    private Query createSizeQuery(SearchQuery.SizeCriterion crit) throws UnsupportedSearchException {
        NumericOperator op = crit.getOperator();
        switch (op.getType()) {
        case EQUALS:
            return NumericRangeQuery.newLongRange(SIZE_FIELD, op.getValue(), op.getValue(), true, true);
        case GREATER_THAN:
            return NumericRangeQuery.newLongRange(SIZE_FIELD, op.getValue(), Long.MAX_VALUE, false, true);
        case LESS_THAN:
            return NumericRangeQuery.newLongRange(SIZE_FIELD, Long.MIN_VALUE, op.getValue(), true, false);
        default:
            throw new UnsupportedSearchException();
        }
    }
    
    /**
     * This method will return the right {@link Query} depending if {@link #suffixMatch} is enabled
     * 
     * @param fieldName
     * @param value
     * @return query
     */
    private Query createTermQuery(String fieldName, String value) {
        if (suffixMatch) {
            return new WildcardQuery(new Term(fieldName, "*" + value + "*"));
        } else {
            return new PrefixQuery(new Term(fieldName, value));
        }
    }
    /**
     * Return a {@link Query} which is build based on the given {@link SearchQuery.HeaderCriterion}
     * 
     * @param crit
     * @return query
     * @throws UnsupportedSearchException
     */
    private Query createHeaderQuery(SearchQuery.HeaderCriterion crit) throws UnsupportedSearchException {
        HeaderOperator op = crit.getOperator();
        String fieldName = PREFIX_HEADER_FIELD + crit.getHeaderName().toLowerCase(Locale.US);
        if (op instanceof SearchQuery.ContainsOperator) {
            ContainsOperator cop = (ContainsOperator) op;
            return createTermQuery(fieldName, cop.getValue().toLowerCase(Locale.US));
        } else if (op instanceof SearchQuery.ExistsOperator){
            return new PrefixQuery(new Term(fieldName, ""));
        } else if (op instanceof SearchQuery.AddressOperator) {
            return createTermQuery(fieldName.toLowerCase(), ((SearchQuery.AddressOperator) op).getAddress().toLowerCase(Locale.US));
        } else {
            // Operator not supported
            throw new UnsupportedSearchException();
        }
    }
    
    /**
     * Return a {@link Query} which is build based on the given {@link SearchQuery.UidCriterion}
     * 
     * @param crit
     * @return query
     * @throws UnsupportedSearchException
     */
    private Query createUidQuery(SearchQuery.UidCriterion crit) throws UnsupportedSearchException {
        NumericRange[] ranges = crit.getOperator().getRange();
        if (ranges.length == 1) {
            NumericRange range = ranges[0];
            return NumericRangeQuery.newLongRange(UID_FIELD, range.getLowValue(), range.getHighValue(), true, true);
        } else {
            BooleanQuery rangesQuery = new BooleanQuery();
            for (int i = 0; i < ranges.length; i++) {
                NumericRange range = ranges[i];
                rangesQuery.add(NumericRangeQuery.newLongRange(UID_FIELD, range.getLowValue(), range.getHighValue(), true, true), BooleanClause.Occur.SHOULD);
            }        
            return rangesQuery;
        }
    }
    
    
    /**
     * Return a {@link Query} which is build based on the given {@link SearchQuery.UidCriterion}
     * 
     * @param crit
     * @return query
     * @throws UnsupportedSearchException
     */
    private Query createModSeqQuery(SearchQuery.ModSeqCriterion crit) throws UnsupportedSearchException {
        NumericOperator op = crit.getOperator();
        switch (op.getType()) {
        case EQUALS:
            return NumericRangeQuery.newLongRange(MODSEQ_FIELD, op.getValue(), op.getValue(), true, true);
        case GREATER_THAN:
            return NumericRangeQuery.newLongRange(MODSEQ_FIELD, op.getValue(), Long.MAX_VALUE, false, true);
        case LESS_THAN:
            return NumericRangeQuery.newLongRange(MODSEQ_FIELD, Long.MIN_VALUE, op.getValue(), true, false);
        default:
            throw new UnsupportedSearchException();
        }
    }
    
    /**
     * Return a {@link Query} which is build based on the given {@link SearchQuery.FlagCriterion}. This is kind of a hack
     * as it will do a search for the flags in this method and 
     * 
     * @param crit
     * @return query
     * @throws UnsupportedSearchException
     */
    private Query createFlagQuery(SearchQuery.FlagCriterion crit, Mailbox<?> mailbox) throws MailboxException, UnsupportedSearchException {
        Flag flag = crit.getFlag();
        String value = toString(flag);
        BooleanQuery query = new BooleanQuery();
        
        if (crit.getOperator().isSet()) {   
            query.add(new TermQuery(new Term(FLAGS_FIELD, value)), BooleanClause.Occur.MUST);
        } else {
            // lucene does not support simple NOT queries so we do some nasty hack here
            BooleanQuery bQuery = new BooleanQuery();
            bQuery.add(new PrefixQuery(new Term(UID_FIELD, "")), BooleanClause.Occur.MUST);
            bQuery.add(new TermQuery(new Term(FLAGS_FIELD, value)),BooleanClause.Occur.MUST_NOT);
            
            query.add(bQuery, BooleanClause.Occur.MUST);
        }
        query.add(new TermQuery(new Term(MAILBOX_ID_FIELD, mailbox.getMailboxId().toString())), BooleanClause.Occur.MUST);
        
        
        IndexSearcher searcher = null;

        try {
            List<Long> uids = new ArrayList<Long>();
            searcher = new IndexSearcher(IndexReader.open(writer, true));
            
            // query for all the documents sorted by uid
            TopDocs docs = searcher.search(query, null, maxQueryResults, UID_SORT);
            ScoreDoc[] sDocs = docs.scoreDocs;
            for (int i = 0; i < sDocs.length; i++) {
                long uid = Long.valueOf(searcher.doc(sDocs[i].doc).get(UID_FIELD));
                uids.add(uid);
            }
            
            List<MessageRange> ranges = MessageRange.toRanges(uids);
            NumericRange[] nRanges = new NumericRange[ranges.size()];
            for (int i = 0; i < ranges.size(); i++) {
                MessageRange range = ranges.get(i);
                nRanges[i] = new NumericRange(range.getUidFrom(), range.getUidTo());
            }
            return createUidQuery((UidCriterion) SearchQuery.uid(nRanges));
        } catch (IOException e) {
            throw new MailboxException("Unable to search mailbox " + mailbox, e);
        } finally {
            if (searcher != null) {
                try {
                    searcher.close();
                } catch (IOException e) {
                    // ignore on close
                }
            }
        }
    }
    
    /**
     * Convert the given {@link Flag} to a String
     * 
     * @param flag
     * @return flagString
     */
    private String toString(Flag flag) {
        if (Flag.ANSWERED.equals(flag)) {
            return "\\ANSWERED";
        } else if (Flag.DELETED.equals(flag)) {
            return "\\DELETED";
        } else if (Flag.DRAFT.equals(flag)) {
            return "\\DRAFT";
        } else if (Flag.FLAGGED.equals(flag)) {
            return "\\FLAGGED";
        } else if (Flag.RECENT.equals(flag)) {
            return "\\RECENT";
        } else if (Flag.SEEN.equals(flag)) {
            return "\\FLAG";
        } else {
            return flag.toString();
        }
    }
    
    /**
     * Return a {@link Query} which is build based on the given {@link SearchQuery.TextCriterion}
     * 
     * @param crit
     * @return query
     * @throws UnsupportedSearchException
     */
    private Query createTextQuery(SearchQuery.TextCriterion crit) throws UnsupportedSearchException {
        String value = crit.getOperator().getValue().toLowerCase(Locale.US);
        switch(crit.getType()) {
        case BODY:
            return createTermQuery(BODY_FIELD, value);
        case FULL: 
            BooleanQuery query = new BooleanQuery();
            query.add(createTermQuery(BODY_FIELD, value), BooleanClause.Occur.SHOULD);
            query.add(createTermQuery(HEADERS_FIELD,value), BooleanClause.Occur.SHOULD);
            return query;
        default:
            throw new UnsupportedSearchException();
        }
    }
    
    /**
     * Return a {@link Query} which is build based on the given {@link SearchQuery.AllCriterion}
     * 
     * @param crit
     * @return query
     * @throws UnsupportedSearchException
     */
    private Query createAllQuery(SearchQuery.AllCriterion crit) throws UnsupportedSearchException{
        BooleanQuery query = new BooleanQuery();
        
        query.add(NumericRangeQuery.newLongRange(UID_FIELD, Long.MIN_VALUE, Long.MAX_VALUE, true, true), BooleanClause.Occur.MUST);
        query.add(new PrefixQuery(new Term(FLAGS_FIELD, "")), BooleanClause.Occur.MUST_NOT);
        
        return query;
    }
    
    /**
     * Return a {@link Query} which is build based on the given {@link SearchQuery.ConjunctionCriterion}
     * 
     * @param crit
     * @return query
     * @throws UnsupportedSearchException
     */
    private Query createConjunctionQuery(SearchQuery.ConjunctionCriterion crit, Mailbox<?> mailbox) throws UnsupportedSearchException, MailboxException {
        BooleanClause.Occur occur;
        switch (crit.getType()) {
        case AND:
            occur = BooleanClause.Occur.MUST;
            break;
        case OR:
            occur = BooleanClause.Occur.SHOULD;
            break;
        case NOR:
            occur = BooleanClause.Occur.MUST_NOT;
            break;
        default:
            throw new UnsupportedSearchException();
        }
        List<Criterion> crits = crit.getCriteria();
        BooleanQuery conQuery = new BooleanQuery();
        for (int i = 0; i < crits.size(); i++) {
            conQuery.add(createQuery(crits.get(i), mailbox), occur);
        }
        if (Conjunction.NOR.equals(crit.getType())) {
            conQuery.add(new TermQuery(new Term(MAILBOX_ID_FIELD, mailbox.getMailboxId().toString())), BooleanClause.Occur.MUST);
        }
        return conQuery;
    }
    
    /**
     * Return a {@link Query} which is builded based on the given {@link Criterion}
     * 
     * @param criterion
     * @return query
     * @throws UnsupportedSearchException
     */
    private Query createQuery(Criterion criterion, Mailbox<?> mailbox) throws UnsupportedSearchException, MailboxException {
        if (criterion instanceof SearchQuery.InternalDateCriterion) {
            SearchQuery.InternalDateCriterion crit = (SearchQuery.InternalDateCriterion) criterion;
            return createInternalDateQuery(crit);
        } else if (criterion instanceof SearchQuery.SizeCriterion) {
            SearchQuery.SizeCriterion crit = (SearchQuery.SizeCriterion) criterion;
            return createSizeQuery(crit);
        } else if (criterion instanceof SearchQuery.HeaderCriterion) {
            HeaderCriterion crit = (HeaderCriterion) criterion;
            return createHeaderQuery(crit);
        } else if (criterion instanceof SearchQuery.UidCriterion) {
            SearchQuery.UidCriterion crit = (SearchQuery.UidCriterion) criterion;
            return createUidQuery(crit);
        } else if (criterion instanceof SearchQuery.FlagCriterion) {
            FlagCriterion crit = (FlagCriterion) criterion;
            return createFlagQuery(crit, mailbox);
        } else if (criterion instanceof SearchQuery.TextCriterion) {
            SearchQuery.TextCriterion crit = (SearchQuery.TextCriterion) criterion;
            return createTextQuery(crit);
        } else if (criterion instanceof SearchQuery.AllCriterion) {
            return createAllQuery((AllCriterion) criterion);
        } else if (criterion instanceof SearchQuery.ConjunctionCriterion) {
            SearchQuery.ConjunctionCriterion crit = (SearchQuery.ConjunctionCriterion) criterion;
            return createConjunctionQuery(crit, mailbox);
        } else if (criterion instanceof SearchQuery.ModSeqCriterion) {
            return createModSeqQuery((SearchQuery.ModSeqCriterion) criterion);
        }
        throw new UnsupportedSearchException();

    }

    

    /*
     * (non-Javadoc)
     * @see org.apache.james.mailbox.store.MessageSearchIndex#add(org.apache.james.mailbox.MailboxSession, org.apache.james.mailbox.store.mail.model.Mailbox, org.apache.james.mailbox.store.mail.model.MailboxMembership)
     */
    public void add(MailboxSession session, Mailbox<Id> mailbox, Message<Id> membership) throws MailboxException {
        Document doc = createMessageDocument(membership);
        Document flagsDoc = createFlagsDocument(membership);

        try {
            writer.addDocument(doc);
            writer.addDocument(flagsDoc);
        } catch (CorruptIndexException e) {
            throw new MailboxException("Unable to add message to index", e);
        } catch (IOException e) {
            throw new MailboxException("Unable to add message to index", e);
        }
    }

    /*
     * (non-Javadoc)
     * @see org.apache.james.mailbox.store.MessageSearchIndex#update(org.apache.james.mailbox.MailboxSession, org.apache.james.mailbox.store.mail.model.Mailbox, org.apache.james.mailbox.MessageRange, javax.mail.Flags)
     */
    public void update(MailboxSession session, Mailbox<Id> mailbox, MessageRange range, Flags f) throws MailboxException {
        try {
            IndexSearcher searcher = new IndexSearcher(IndexReader.open(writer, true));
            BooleanQuery query = new BooleanQuery();
            query.add(new TermQuery(new Term(MAILBOX_ID_FIELD, mailbox.getMailboxId().toString())), BooleanClause.Occur.MUST);
            query.add(NumericRangeQuery.newLongRange(UID_FIELD, range.getUidFrom(), range.getUidTo(), true, true), BooleanClause.Occur.MUST);
            query.add( new PrefixQuery(new Term(FLAGS_FIELD, "")), BooleanClause.Occur.MUST);

            TopDocs docs = searcher.search(query, 100000);
            ScoreDoc[] sDocs = docs.scoreDocs;
            for (int i = 0; i < sDocs.length; i++) {
                Document doc = searcher.doc(sDocs[i].doc);
                doc.removeFields(FLAGS_FIELD);
                indexFlags(doc, f);
                writer.updateDocument(new Term(ID_FIELD, doc.get(ID_FIELD)), doc);
            }
        } catch (IOException e) {
            throw new MailboxException("Unable to add messages in index", e);

        }
        
    }

    /**
     * Index the {@link Flags} and add it to the {@link Document}
     * 
     * @param f
     * @param doc
     */
    private Document createFlagsDocument(Message<?> message) {
        Document doc = new Document();
        doc.add(new Field(ID_FIELD, "flags-" + message.getMailboxId().toString() +"-" + Long.toString(message.getUid()), Store.YES, Index.NOT_ANALYZED));
        doc.add(new Field(MAILBOX_ID_FIELD, message.getMailboxId().toString(), Store.YES, Index.NOT_ANALYZED));
        doc.add(new NumericField(UID_FIELD,Store.YES, true).setLongValue(message.getUid()));
        
        indexFlags(doc, message.createFlags());
        return doc;
    }
    
    /**
     * Add the given {@link Flags} to the {@link Document}
     * 
     * @param doc
     * @param f
     */
    private void indexFlags(Document doc, Flags f) {
        Flag[] flags = f.getSystemFlags();
        for (int a = 0; a < flags.length; a++) {
            doc.add(new Field(FLAGS_FIELD, toString(flags[a]),Store.NO, Index.NOT_ANALYZED));
        }
        
        String[] userFlags = f.getUserFlags();
        for (int a = 0; a < userFlags.length; a++) {
            doc.add(new Field(FLAGS_FIELD, userFlags[a],Store.NO, Index.NOT_ANALYZED));
        }
    }
    /*
     * (non-Javadoc)
     * @see org.apache.james.mailbox.store.MessageSearchIndex#delete(org.apache.james.mailbox.MailboxSession, org.apache.james.mailbox.store.mail.model.Mailbox, org.apache.james.mailbox.MessageRange)
     */
    public void delete(MailboxSession session, Mailbox<Id> mailbox, MessageRange range) throws MailboxException {
        BooleanQuery query = new BooleanQuery();
        query.add(new TermQuery(new Term(MAILBOX_ID_FIELD, mailbox.getMailboxId().toString())), BooleanClause.Occur.MUST);
        query.add(NumericRangeQuery.newLongRange(UID_FIELD, range.getUidFrom(), range.getUidTo(), true, true), BooleanClause.Occur.MUST);
        
        try {
            writer.deleteDocuments(query);
        } catch (CorruptIndexException e) {
            throw new MailboxException("Unable to delete message from index", e);

        } catch (IOException e) {
            throw new MailboxException("Unable to delete message from index", e);
        }
    }
    


}