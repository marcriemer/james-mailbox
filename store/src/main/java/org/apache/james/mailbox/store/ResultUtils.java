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

package org.apache.james.mailbox.store;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.apache.james.mailbox.Content;
import org.apache.james.mailbox.MailboxException;
import org.apache.james.mailbox.MessageResult;
import org.apache.james.mailbox.MessageResult.FetchGroup;
import org.apache.james.mailbox.MessageResult.MimePath;
import org.apache.james.mailbox.MimeDescriptor;
import org.apache.james.mailbox.store.mail.model.Message;
import org.apache.james.mailbox.store.streaming.InputStreamContent;
import org.apache.james.mailbox.store.streaming.InputStreamContent.Type;
import org.apache.james.mailbox.store.streaming.PartContentBuilder;
import org.apache.james.mime4j.MimeException;
import org.apache.james.mime4j.parser.AbstractContentHandler;
import org.apache.james.mime4j.parser.MimeStreamParser;
import org.apache.james.mime4j.stream.Field;
import org.apache.james.mime4j.stream.MimeConfig;
import org.apache.james.mime4j.stream.RawField;
import org.apache.james.mime4j.util.ByteSequence;
import org.apache.james.mime4j.util.ContentUtil;

/**
 *
 */
public class ResultUtils {

    public static final byte[] BYTES_NEW_LINE = { 0x0D, 0x0A };

    public static final byte[] BYTES_HEADER_FIELD_VALUE_SEP = { 0x3A, 0x20 };

    static final Charset US_ASCII = Charset.forName("US-ASCII");

    public static List<MessageResult.Header> createHeaders(final Message<?> document) throws IOException {
        final List<MessageResult.Header> results = new ArrayList<MessageResult.Header>();
        MimeConfig config = new MimeConfig();
        config.setMaxLineLen(-1);
        config.setMaxHeaderLen(-1);
        final MimeStreamParser parser = new MimeStreamParser(config);
        parser.setContentHandler(new AbstractContentHandler() {
            @Override
            public void endHeader() {
                parser.stop();
            }
            @Override
            public void field(Field field) throws MimeException {
                String fieldValue;
                if (field instanceof RawField) {
                    // check if we can access the body in the raw form so no unfolding was done under the hood
                    ByteSequence raw = field.getRaw();
                    int len = raw.length();
                    int off = ((RawField) field).getDelimiterIdx() + 1;
                    if (len > off + 1 && (raw.byteAt(off) & 0xff) == 0x20) off++;
                
                    fieldValue = ContentUtil.decode(raw, off, len - off);
                } else {
                    fieldValue = field.getBody();
                }
                if (fieldValue.endsWith("\r\f")) {
                    fieldValue = fieldValue.substring(0,fieldValue.length() - 2);
                }
                if (fieldValue.startsWith(" ")) {
                    fieldValue = fieldValue.substring(1);
                }
                
                final ResultHeader resultHeader = new ResultHeader(field.getName(), fieldValue);
                results.add(resultHeader);
            }
        });
        try {
            // add the header seperator to the stream to mime4j don't log a warning
            parser.parse(new SequenceInputStream(document.getHeaderContent(), new ByteArrayInputStream(BYTES_NEW_LINE)));
        } catch (MimeException e) {
            throw new IOException("Unable to parse headers of message " + document, e);
        }
        return results;
    }

    /**
     * Return the {@link Content} which holds only the Body for the given {@link MailboxMembership}
     * 
     * @param membership
     * @return bodyContent
     * @throws IOException 
     */
    public static Content createBodyContent(Message<?> membership) throws IOException {
        final InputStreamContent result = new InputStreamContent(membership, Type.Body);
        return result;
    }

    /**
     * Return the {@link Content} which holds the full data for the given {@link MailboxMembership}
     * 
     * @param membership
     * @return content
     * @throws IOException 
     */
    public static Content createFullContent(final Message<?> membership) throws IOException {
        final InputStreamContent result = new InputStreamContent(membership, Type.Full);
        return result;
    }

    /**
     * Return an {@link InputStream} which holds the full content of the message
     * @param message
     * @return
     * @throws IOException
     */
    public static InputStream toInput(final Message<?> message) throws IOException{
        return toInput(message.getHeaderContent(), message.getBodyContent());
    }
    
    public static InputStream toInput(final InputStream header, final InputStream body) {
        return new SequenceInputStream(Collections.enumeration(Arrays.asList(header, new ByteArrayInputStream(BYTES_NEW_LINE), body)));
    }
    
    /**
     * Return the {@link MessageResult} for the given {@link MailboxMembership} and {@link FetchGroup}
     * 
     * @param message
     * @param fetchGroup
     * @return result
     * @throws MailboxException
     */
    public static MessageResult loadMessageResult(final Message<?> message, final FetchGroup fetchGroup) throws MailboxException {
        try {

            MessageResultImpl messageResult = new MessageResultImpl(message);
            if (fetchGroup != null) {
                int content = fetchGroup.content();

                if ((content & FetchGroup.HEADERS) > 0) {
                    content -= FetchGroup.HEADERS;
                }
                if ((content & FetchGroup.BODY_CONTENT) > 0) {
                    content -= FetchGroup.BODY_CONTENT;
                }
                if ((content & FetchGroup.FULL_CONTENT) > 0) {
                    content -= FetchGroup.FULL_CONTENT;
                }
                if ((content & FetchGroup.MIME_DESCRIPTOR) > 0) {
                    addMimeDescriptor(message, messageResult);
                    content -= FetchGroup.MIME_DESCRIPTOR;
                }
                if (content != 0) {
                    throw new UnsupportedOperationException("Unsupported result: " + content);
                }

                addPartContent(fetchGroup, message, messageResult);
            }
            return messageResult;

        } catch (IOException e) {
            throw new MailboxException("Unable to parse message", e);
        } catch (MimeException e) {
            throw new MailboxException("Unable to parse message", e);
        }

    }

    private static void addMimeDescriptor(Message<?> message, MessageResultImpl messageResult) throws IOException, MimeException {
            MimeDescriptor descriptor = MimeDescriptorImpl.build(message);
            messageResult.setMimeDescriptor(descriptor);
    }


    private static void addPartContent(final FetchGroup fetchGroup,
            Message<?> message, MessageResultImpl messageResult)
            throws MailboxException, IOException,
            MimeException {
        Collection<FetchGroup.PartContentDescriptor> partContent = fetchGroup.getPartContentDescriptors();
        if (partContent != null) {
            for (FetchGroup.PartContentDescriptor descriptor: partContent) {
                addPartContent(descriptor, message, messageResult);
            }
        }
    }

    private static void addPartContent(
            FetchGroup.PartContentDescriptor descriptor, Message<?> message,
            MessageResultImpl messageResult) throws 
            MailboxException, IOException, MimeException {
        final MimePath mimePath = descriptor.path();
        final int content = descriptor.content();
        if ((content & MessageResult.FetchGroup.FULL_CONTENT) > 0) {
            addFullContent(message, messageResult, mimePath);
        }
        if ((content & MessageResult.FetchGroup.BODY_CONTENT) > 0) {
            addBodyContent(message, messageResult, mimePath);
        }
        if ((content & MessageResult.FetchGroup.MIME_CONTENT) > 0) {
            addMimeBodyContent(message, messageResult, mimePath);
        }
        if ((content & MessageResult.FetchGroup.HEADERS) > 0) {
            addHeaders(message, messageResult, mimePath);
        }
        if ((content & MessageResult.FetchGroup.MIME_HEADERS) > 0) {
            addMimeHeaders(message, messageResult, mimePath);
        }
    }

    private static PartContentBuilder build(int[] path, final Message<?> message)
            throws IOException, MimeException {
        final InputStream stream = toInput(message);
        PartContentBuilder result = new PartContentBuilder();
        result.parse(stream);
        try {
            for (int i = 0; i < path.length; i++) {
                final int next = path[i];
                result.to(next);
            }
        } catch (PartContentBuilder.PartNotFoundException e) {
            // Missing parts should return zero sized content
            // See http://markmail.org/message/2jconrj7scvdi5dj
            result.markEmpty();
        }
        return result;
    }
   

  
    private static final int[] path(MimePath mimePath) {
        final int[] result;
        if (mimePath == null) {
            result = null;
        } else {
            result = mimePath.getPositions();
        }
        return result;
    }

    private static void addHeaders(Message<?> message,
            MessageResultImpl messageResult, MimePath mimePath)
            throws IOException, MimeException {
        final int[] path = path(mimePath);
        if (path != null) {
       
            final PartContentBuilder builder = build(path, message);
            final List<MessageResult.Header> headers = builder.getMessageHeaders();
            messageResult.setHeaders(mimePath, headers.iterator());
        }
    }

    private static void addMimeHeaders(Message<?> message,
            MessageResultImpl messageResult, MimePath mimePath)
            throws IOException, MimeException {
        final int[] path = path(mimePath);
        if (path != null) {
            final PartContentBuilder builder = build(path, message);
            final List<MessageResult.Header> headers = builder.getMimeHeaders();
            messageResult.setMimeHeaders(mimePath, headers.iterator());
        }
    }

    private static void addBodyContent(Message<?> message,
            MessageResultImpl messageResult, MimePath mimePath) throws IOException, MimeException {
        final int[] path = path(mimePath);
        if (path != null) {
            final PartContentBuilder builder = build(path, message);
            final Content content = builder.getMessageBodyContent();
            messageResult.setBodyContent(mimePath, content);
        }
    }

    private static void addMimeBodyContent(Message<?> message,
            MessageResultImpl messageResult, MimePath mimePath)
            throws IOException, MimeException {
        final int[] path = path(mimePath);
        final PartContentBuilder builder = build(path, message);
        final Content content = builder.getMimeBodyContent();
        messageResult.setMimeBodyContent(mimePath, content);
    }

    private static void addFullContent(Message<?> message,
            MessageResultImpl messageResult, MimePath mimePath)
            throws MailboxException, IOException,
            MimeException {
        final int[] path = path(mimePath);
        if (path != null) {
            final PartContentBuilder builder = build(path, message);
            final Content content = builder.getFullContent();
            messageResult.setFullContent(mimePath, content);
        }
    }
}
