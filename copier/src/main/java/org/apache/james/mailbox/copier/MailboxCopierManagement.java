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
package org.apache.james.mailbox.copier;

import javax.annotation.Resource;
import javax.management.NotCompliantMBeanException;
import javax.management.StandardMBean;

/**
 * Implementation of the {@link MailboxCopierManagementMBean} JMX Management interface.
 *
 */
public class MailboxCopierManagement extends StandardMBean implements MailboxCopierManagementMBean {
    
    /**
     * Inject the mailboxCopier bean.
     */
    @Resource(name="mailboxCopier")
    private MailboxCopier mailboxCopier;
    
    /**
     * Default Constructor.
     * 
     * @throws NotCompliantMBeanException
     */
    public MailboxCopierManagement() throws NotCompliantMBeanException {
        super(MailboxCopierManagementMBean.class);
    }
    
    /* (non-Javadoc)
     * @see org.apache.james.mailbox.copier.MailboxCopier#copyMailboxes()
     */
    public boolean copyMailboxes() {
        return mailboxCopier.copyMailboxes();
    }

}
