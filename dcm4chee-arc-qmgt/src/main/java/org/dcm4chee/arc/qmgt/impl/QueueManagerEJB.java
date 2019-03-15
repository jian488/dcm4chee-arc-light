/*
 * *** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is part of dcm4che, an implementation of DICOM(TM) in
 * Java(TM), hosted at https://github.com/dcm4che.
 *
 * The Initial Developer of the Original Code is
 * J4Care.
 * Portions created by the Initial Developer are Copyright (C) 2015-2019
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 * See @authors listed below
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * *** END LICENSE BLOCK *****
 */

package org.dcm4chee.arc.qmgt.impl;

import com.mysema.commons.lang.CloseableIterator;
import com.querydsl.core.types.Predicate;
import com.querydsl.jpa.hibernate.HibernateQuery;
import com.querydsl.jpa.hibernate.HibernateUpdateClause;
import org.dcm4che3.net.Device;
import org.dcm4chee.arc.conf.ArchiveDeviceExtension;
import org.dcm4chee.arc.conf.QueueDescriptor;
import org.dcm4chee.arc.entity.*;
import org.dcm4chee.arc.event.QueueMessageEvent;
import org.dcm4chee.arc.qmgt.*;
import org.dcm4chee.arc.query.util.MatchTask;
import org.dcm4chee.arc.query.util.TaskQueryParam;
import org.hibernate.Session;
import org.hibernate.annotations.QueryHints;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.enterprise.event.Event;
import javax.inject.Inject;
import javax.jms.*;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.persistence.*;
import javax.persistence.criteria.*;
import java.io.Serializable;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

/**
 * @author Gunter Zeilinger <gunterze@gmail.com>
 * @author Vrinda Nayak <vrinda.nayak@j4care.com>
 * @since Sep 2015
 */
@Stateless
public class QueueManagerEJB {

    private static final Logger LOG = LoggerFactory.getLogger(QueueManagerEJB.class);

    private Root<QueueMessage> queueMsg;

    @PersistenceContext(unitName="dcm4chee-arc")
    private EntityManager em;

    @Inject
    private JMSContext jmsCtx;

    @Inject
    private Device device;

    @Inject
    private Event<MessageCanceled> messageCanceledEvent;

    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public ObjectMessage createObjectMessage(Serializable object) {
        return jmsCtx.createObjectMessage(object);
    }

    public QueueMessage scheduleMessage(String queueName, ObjectMessage msg, int priority, String batchID, long delay)
            throws QueueSizeLimitExceededException {
        QueueDescriptor queueDescriptor = descriptorOf(queueName);
        int maxQueueSize = queueDescriptor.getMaxQueueSize();
        if (maxQueueSize > 0 && maxQueueSize < countScheduledMessagesOnThisDevice(queueName))
            throw new QueueSizeLimitExceededException(queueDescriptor);

        sendMessage(queueDescriptor, msg, delay, priority);
        QueueMessage entity = new QueueMessage(device.getDeviceName(), queueName, msg, delay);
        entity.setBatchID(batchID);
        em.persist(entity);
        LOG.info("Schedule Task[id={}] at Queue {}", entity.getMessageID(), entity.getQueueName());
        return entity;
    }

    public long countScheduledMessagesOnThisDevice(String queueName) {
        return em.createNamedQuery(QueueMessage.COUNT_BY_DEVICE_AND_QUEUE_NAME_AND_STATUS, Long.class)
                .setParameter(1, device.getDeviceName())
                .setParameter(2, queueName)
                .setParameter(3, QueueMessage.Status.SCHEDULED).getSingleResult();
    }

    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public QueueMessage onProcessingStart(String msgId) {
        QueueMessage entity = findQueueMessage(msgId);
        if (entity == null) {
            LOG.info("Suppress processing of already deleted Task[id={}]", msgId);
        } else switch (entity.getStatus()) {
            case IN_PROCESS:
            case SCHEDULED:
                LOG.info("Start processing Task[id={}] from Queue {} with Status: {}",
                        entity.getMessageID(), entity.getQueueName(), entity.getStatus());
                entity.setProcessingStartTime(new Date());
                entity.setStatus(QueueMessage.Status.IN_PROCESS);
                setUpdateTime(entity);
                return entity;
            default:
                LOG.info("Suppress processing of Task[id={}] from Queue {} with Status: {}",
                        msgId, entity.getQueueName(), entity.getStatus());
        }
        return null;
    }

    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public QueueMessage onProcessingSuccessful(String msgId, Outcome outcome) {
        QueueMessage entity = findQueueMessage(msgId);
        if (entity == null) {
            LOG.info("Finished processing of Task[id={}]", msgId);
            return null;
        }
        QueueMessage.Status status = outcome.getStatus();
        String queueName = entity.getQueueName();
        entity.setProcessingEndTime(new Date());
        entity.setOutcomeMessage(outcome.getDescription());
        entity.setStatus(status);
        setUpdateTime(entity);
        if (status == QueueMessage.Status.COMPLETED
                || status == QueueMessage.Status.WARNING && !descriptorOf(queueName).isRetryOnWarning()) {
            LOG.info("Finished processing of Task[id={}] at Queue {} with Status {}", msgId, queueName, status);
            return entity;
        }
        QueueDescriptor descriptor = descriptorOf(queueName);
        long delay = descriptor.getRetryDelayInSeconds(entity.incrementNumberOfFailures());
        if (delay >= 0) {
            LOG.info("Failed processing of Task[id={}] at Queue {} with Status {} - retry",
                    msgId, queueName, status);
            entity.setStatus(QueueMessage.Status.SCHEDULED);
            rescheduleTask(entity, descriptor, delay * 1000L);
            return entity;
        }
        LOG.warn("Failed processing of Task[id={}] at Queue {} with Status {}", msgId, queueName, status);
        entity.setStatus(status);
        return entity;
    }

    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public QueueMessage onProcessingFailed(String msgId, Throwable e) {
        QueueMessage entity = findQueueMessage(msgId);
        if (entity == null) {
            LOG.warn("Failed processing of Task[id={}]:\n", msgId, e);
            return null;
        }

        entity.setErrorMessage(e.getMessage());
        entity.setProcessingEndTime(new Date());
        QueueDescriptor descriptor = descriptorOf(entity.getQueueName());
        long delay = descriptor.getRetryDelayInSeconds(entity.incrementNumberOfFailures());
        if (delay < 0) {
            LOG.warn("Failed processing of Task[id={}] at Queue {}:\n", msgId, entity.getQueueName(), e);
            entity.setStatus(QueueMessage.Status.FAILED);
            setUpdateTime(entity);
        } else {
            LOG.info("Failed processing of Task[id={}] at Queue {} - retry:\n", msgId, entity.getQueueName(), e);
            rescheduleTask(entity, descriptor, delay * 1000L);
        }
        return entity;
    }

    public boolean cancelTask(String msgId, QueueMessageEvent queueEvent) throws IllegalTaskStateException {
        QueueMessage entity = findQueueMessage(msgId);
        if (entity == null)
            return false;

        if (queueEvent != null)
            queueEvent.setQueueMsg(entity);

        switch (entity.getStatus()) {
            case COMPLETED:
            case CANCELED:
            case WARNING:
            case FAILED:
                throw new IllegalTaskStateException(
                        "Cannot cancel Task[id=" + msgId + "] with Status: " + entity.getStatus());
        }
        cancelTask(entity);
        return true;
    }

    private void cancelTask(QueueMessage entity) {
        entity.setStatus(QueueMessage.Status.CANCELED);
        setUpdateTime(entity);
        LOG.info("Cancel processing of Task[id={}] at Queue {}", entity.getMessageID(), entity.getQueueName());
        messageCanceledEvent.fire(new MessageCanceled(entity.getMessageID()));
    }

    private void setUpdateTime(QueueMessage entity) {
        if (entity.getExportTask() != null)
            entity.getExportTask().setUpdatedTime();
        else if (entity.getRetrieveTask() != null)
            entity.getRetrieveTask().setUpdatedTime();
        else if (entity.getDiffTask() != null)
            entity.getDiffTask().setUpdatedTime();
        else if (entity.getStorageVerificationTask() != null)
            entity.getStorageVerificationTask().setUpdatedTime();
    }

    public long cancelTasks(Predicate matchQueueMessage) {
        Date now = new Date();
        HibernateQuery<Long> queueMessageQuery = new HibernateQuery<Long>(em.unwrap(Session.class))
                .select(QQueueMessage.queueMessage.pk)
                .from(QQueueMessage.queueMessage)
                .where(matchQueueMessage);
        updateExportTaskUpdatedTime(queueMessageQuery, now);
        updateRetrieveTaskUpdatedTime(queueMessageQuery, now);
        updateDiffTaskUpdatedTime(queueMessageQuery, now);
        updateStgVerTaskUpdatedTime(queueMessageQuery, now);
        return updateStatus(queueMessageQuery, QueueMessage.Status.CANCELED, now);
    }

    private void updateExportTaskUpdatedTime(HibernateQuery<Long> queueMessageQuery, Date now) {
        new HibernateUpdateClause(em.unwrap(Session.class), QExportTask.exportTask)
                .set(QExportTask.exportTask.updatedTime, now)
                .where(QExportTask.exportTask.queueMessage.pk.in(queueMessageQuery))
                .execute();
    }

    private void updateRetrieveTaskUpdatedTime(HibernateQuery<Long> queueMessageQuery, Date now) {
        new HibernateUpdateClause(em.unwrap(Session.class), QRetrieveTask.retrieveTask)
                .set(QRetrieveTask.retrieveTask.updatedTime, now)
                .where(QRetrieveTask.retrieveTask.queueMessage.pk.in(queueMessageQuery))
                .execute();
    }

    private void updateDiffTaskUpdatedTime(HibernateQuery<Long> queueMessageQuery, Date now) {
        new HibernateUpdateClause(em.unwrap(Session.class), QDiffTask.diffTask)
                .set(QDiffTask.diffTask.updatedTime, now)
                .where(QDiffTask.diffTask.queueMessage.pk.in(queueMessageQuery))
                .execute();
    }

    private void updateStgVerTaskUpdatedTime(HibernateQuery<Long> queueMessageQuery, Date now) {
        new HibernateUpdateClause(em.unwrap(Session.class), QStorageVerificationTask.storageVerificationTask)
                .set(QStorageVerificationTask.storageVerificationTask.updatedTime, now)
                .where(QStorageVerificationTask.storageVerificationTask.queueMessage.pk.in(queueMessageQuery))
                .execute();
    }

    private long updateStatus(HibernateQuery<Long> queueMessageQuery, QueueMessage.Status status, Date now) {
        return new HibernateUpdateClause(em.unwrap(Session.class), QQueueMessage.queueMessage)
                .set(QQueueMessage.queueMessage.status, status)
                .set(QQueueMessage.queueMessage.updatedTime, now)
                .where(QQueueMessage.queueMessage.pk.in(queueMessageQuery))
                .execute();
    }

    public long cancelExportTasks(Predicate matchQueueMessage, Predicate matchExportTask) {
        Date now = new Date();
        HibernateQuery<Long> queueMessageQuery = new HibernateQuery<Long>(em.unwrap(Session.class))
                .select(QExportTask.exportTask.queueMessage.pk)
                .from(QExportTask.exportTask)
                .join(QExportTask.exportTask.queueMessage, QQueueMessage.queueMessage)
                .on(matchQueueMessage)
                .where(matchExportTask);
        updateExportTaskUpdatedTime(queueMessageQuery, now);
        return updateStatus(queueMessageQuery, QueueMessage.Status.CANCELED, now);
    }

    public List<String> getExportTasksReferencedQueueMsgIDs(Predicate matchQueueMessage, Predicate matchExportTask) {
        return new HibernateQuery<String>(em.unwrap(Session.class))
                .select(QExportTask.exportTask.queueMessage.messageID)
                .from(QExportTask.exportTask)
                .join(QExportTask.exportTask.queueMessage, QQueueMessage.queueMessage)
                .on(matchQueueMessage)
                .where(matchExportTask)
                .fetch();
    }

    public long cancelRetrieveTasks(Predicate matchQueueMessage, Predicate matchRetrieveTask) {
        Date now = new Date();
        HibernateQuery<Long> queueMessageQuery = new HibernateQuery<Long>(em.unwrap(Session.class))
                .select(QRetrieveTask.retrieveTask.queueMessage.pk)
                .from(QRetrieveTask.retrieveTask)
                .join(QRetrieveTask.retrieveTask.queueMessage, QQueueMessage.queueMessage)
                .on(matchQueueMessage)
                .where(matchRetrieveTask);
        updateRetrieveTaskUpdatedTime(queueMessageQuery, now);
        return updateStatus(queueMessageQuery, QueueMessage.Status.CANCELED, now);
    }

    public long cancelDiffTasks(Predicate matchQueueMessage, Predicate matchDiffTask) {
        Date now = new Date();
        HibernateQuery<Long> queueMessageQuery = new HibernateQuery<Long>(em.unwrap(Session.class))
                .select(QDiffTask.diffTask.queueMessage.pk)
                .from(QDiffTask.diffTask)
                .join(QDiffTask.diffTask.queueMessage, QQueueMessage.queueMessage)
                .on(matchQueueMessage)
                .where(matchDiffTask);
        updateDiffTaskUpdatedTime(queueMessageQuery, now);
        return updateStatus(queueMessageQuery, QueueMessage.Status.CANCELED, now);
    }

    public long cancelStgVerTasks(Predicate matchQueueMessage, Predicate matchStgVerTask) {
        Date now = new Date();
        HibernateQuery<Long> queueMessageQuery = new HibernateQuery<Long>(em.unwrap(Session.class))
                .select(QStorageVerificationTask.storageVerificationTask.queueMessage.pk)
                .from(QStorageVerificationTask.storageVerificationTask)
                .join(QStorageVerificationTask.storageVerificationTask.queueMessage, QQueueMessage.queueMessage)
                .on(matchQueueMessage)
                .where(matchStgVerTask);
        updateStgVerTaskUpdatedTime(queueMessageQuery, now);
        return updateStatus(queueMessageQuery, QueueMessage.Status.CANCELED, now);
    }

    public List<String> getRetrieveTasksReferencedQueueMsgIDs(Predicate matchQueueMessage, Predicate matchRetrieveTask) {
        return new HibernateQuery<String>(em.unwrap(Session.class))
                .select(QRetrieveTask.retrieveTask.queueMessage.messageID)
                .from(QRetrieveTask.retrieveTask)
                .join(QRetrieveTask.retrieveTask.queueMessage, QQueueMessage.queueMessage)
                .on(matchQueueMessage)
                .where(matchRetrieveTask)
                .fetch();
    }

    public List<String> getDiffTasksReferencedQueueMsgIDs(Predicate matchQueueMessage, Predicate matchDiffTask) {
        return new HibernateQuery<String>(em.unwrap(Session.class))
                .select(QDiffTask.diffTask.queueMessage.messageID)
                .from(QDiffTask.diffTask)
                .join(QDiffTask.diffTask.queueMessage, QQueueMessage.queueMessage)
                .on(matchQueueMessage)
                .where(matchDiffTask)
                .fetch();
    }

    public List<String> getStgVerTasksReferencedQueueMsgIDs(Predicate matchQueueMessage, Predicate matchStgVerTask) {
        return new HibernateQuery<String>(em.unwrap(Session.class))
                .select(QStorageVerificationTask.storageVerificationTask.queueMessage.messageID)
                .from(QStorageVerificationTask.storageVerificationTask)
                .join(QStorageVerificationTask.storageVerificationTask.queueMessage, QQueueMessage.queueMessage)
                .on(matchQueueMessage)
                .where(matchStgVerTask)
                .fetch();
    }

    public String findDeviceNameByMsgId(String msgId) {
        try {
            return em.createNamedQuery(QueueMessage.FIND_DEVICE_BY_MSG_ID, String.class)
                    .setParameter(1, msgId)
                    .getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    public void rescheduleTask(String msgId, String queueName, QueueMessageEvent queueEvent) {
        QueueMessage entity = findQueueMessage(msgId);
        if (entity == null)
            return;

        if (queueEvent != null)
            queueEvent.setQueueMsg(entity);

        switch (entity.getStatus()) {
            case SCHEDULED:
            case IN_PROCESS:
                cancelTask(entity);
        }
        if (queueName != null)
            entity.setQueueName(queueName);
        entity.setNumberOfFailures(0);
        entity.setErrorMessage(null);
        entity.setOutcomeMessage(null);
        entity.updateExporterIDInMessageProperties();
        rescheduleTask(entity, descriptorOf(entity.getQueueName()), 0L);
    }

    private void rescheduleTask(QueueMessage entity, QueueDescriptor descriptor, long delay) {
        try {
            ObjectMessage msg = entity.initProperties(createObjectMessage(entity.getMessageBody()));
            sendMessage(descriptor, msg, delay, entity.getPriority());
            entity.setMessageID(msg.getJMSMessageID());
            entity.setScheduledTime(new Date(System.currentTimeMillis() + delay));
            entity.setStatus(QueueMessage.Status.SCHEDULED);
            entity.setDeviceName(device.getDeviceName());
            setUpdateTime(entity);
            LOG.info("Reschedule Task[id={}] at Queue {}", entity.getMessageID(), entity.getQueueName());
        } catch (JMSException e) {
            throw toJMSRuntimeException(e);
        }
    }

    private JMSRuntimeException toJMSRuntimeException(JMSException e) {
        return new JMSRuntimeException(e.getMessage(), e.getErrorCode(), e.getCause());
    }

    public boolean deleteTask(String msgId, QueueMessageEvent queueEvent) {
        QueueMessage entity = findQueueMessage(msgId);
        if (entity == null)
            return false;

        if (queueEvent != null)
            queueEvent.setQueueMsg(entity);
        deleteTask(entity);
        return true;
    }

    private void deleteTask(QueueMessage entity) {
        if (entity.getStatus() == QueueMessage.Status.IN_PROCESS)
            messageCanceledEvent.fire(new MessageCanceled(entity.getMessageID()));

        if (entity.getExportTask() != null)
            em.remove(entity.getExportTask());
        else if (entity.getRetrieveTask() != null)
            em.remove(entity.getRetrieveTask());
        else if (entity.getDiffTask() != null)
            em.remove(entity.getDiffTask());
        else if (entity.getStorageVerificationTask() != null)
            em.remove(entity.getStorageVerificationTask());
        else
            em.remove(entity);
        LOG.info("Delete Task[id={}] from Queue {}", entity.getMessageID(), entity.getQueueName());
    }

    public int deleteTasks(Predicate matchQueueMessage, int deleteTaskFetchSize) {
        int count = 0;
        try (CloseableIterator<QueueMessage> iterate = createQuery(matchQueueMessage).limit(deleteTaskFetchSize).iterate()) {
            while (iterate.hasNext()) {
                deleteTask(iterate.next());
                count++;
            }
        }
        return count;
    }

    private HibernateQuery<QueueMessage> createQuery(Predicate matchQueueMessage) {
        return new HibernateQuery<QueueMessage>(em.unwrap(Session.class))
                .from(QQueueMessage.queueMessage)
                .where(matchQueueMessage);
    }

    private CriteriaQuery<String> createQuery(TaskQueryParam queueTaskQueryParam) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        MatchTask matchTask = new MatchTask(cb);
        CriteriaQuery<String> q = cb.createQuery(String.class);
        queueMsg = q.from(QueueMessage.class);
        return restrict(queueTaskQueryParam, matchTask, q);
    }

    public List<String> getQueueMsgIDs(Predicate matchQueueMessage, int limit) {
        HibernateQuery<String> queueMsgIDsQuery = createQuery(matchQueueMessage)
                .select(QQueueMessage.queueMessage.messageID);
        if (limit > 0)
            queueMsgIDsQuery.limit(limit);
        return queueMsgIDsQuery.fetch();
    }

    public List<String> getQueueMsgIDs(TaskQueryParam queueTaskQueryParam, int limit) {
        return em.createQuery(createQuery(queueTaskQueryParam)
                .select(queueMsg.get(QueueMessage_.messageID)))
                .setMaxResults(limit)
                .getResultList();
    }

    public List<String> listDistinctDeviceNames(TaskQueryParam queueTaskQueryParam) {
        return em.createQuery(createQuery(queueTaskQueryParam)
                .select(queueMsg.get(QueueMessage_.deviceName))
                .distinct(true))
                .getResultList();
    }

    private void sendMessage(QueueDescriptor desc, ObjectMessage msg, long delay, int priority) {
        jmsCtx.createProducer().setDeliveryDelay(delay).setPriority(priority).send(lookup(desc.getJndiName()), msg);
    }

    private Queue lookup(String jndiName) {
        try {
            return InitialContext.doLookup(jndiName);
        } catch (NamingException e) {
            throw new RuntimeException(e);
        }
    }

    private QueueDescriptor descriptorOf(String queueName) {
        return device.getDeviceExtensionNotNull(ArchiveDeviceExtension.class).getQueueDescriptorNotNull(queueName);
    }

    private QueueMessage findQueueMessage(String msgId) {
        try {
            return em.createNamedQuery(QueueMessage.FIND_BY_MSG_ID, QueueMessage.class)
                    .setParameter(1, msgId)
                    .getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public Iterator<QueueMessage> listQueueMessages(
            TaskQueryParam queueTaskQueryParam, int offset, int limit) {
        return listQueueMessages(queueTaskQueryParam, queryFetchSize(), offset, limit);
    }

    private Iterator<QueueMessage> listQueueMessages(
            TaskQueryParam queueTaskQueryParam, int fetchSize, int offset, int limit) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        MatchTask matchTask = new MatchTask(cb);
        TypedQuery<QueueMessage> query = em.createQuery(select(cb, matchTask, queueTaskQueryParam));
        if (fetchSize > 0)
            query.setHint(QueryHints.FETCH_SIZE, fetchSize);
        if (offset > 0)
            query.setFirstResult(offset);
        if (limit > 0)
            query.setMaxResults(limit);
        return query.getResultStream().iterator();
    }

    public long countTasks(TaskQueryParam queueTaskQueryParam) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        MatchTask matchTask = new MatchTask(cb);

        CriteriaQuery<Long> q = cb.createQuery(Long.class);
        queueMsg = q.from(QueueMessage.class);

        return em.createQuery(
                restrict(queueTaskQueryParam, matchTask, q).select(cb.count(queueMsg)))
                .getSingleResult();
    }

    private <T> CriteriaQuery<T> restrict(
            TaskQueryParam queueTaskQueryParam, MatchTask matchTask, CriteriaQuery<T> q) {
        List<javax.persistence.criteria.Predicate> predicates = matchTask.queueMsgPredicates(
                queueMsg,
                queueTaskQueryParam);
        if (!predicates.isEmpty())
            q.where(predicates.toArray(new javax.persistence.criteria.Predicate[0]));
        return q;
    }

    private int queryFetchSize() {
        return device.getDeviceExtensionNotNull(ArchiveDeviceExtension.class).getQueryFetchSize();
    }

    private CriteriaQuery<QueueMessage> select(CriteriaBuilder cb,
                                               MatchTask matchTask, TaskQueryParam queueTaskQueryParam) {
        CriteriaQuery<QueueMessage> q = cb.createQuery(QueueMessage.class);
        queueMsg = q.from(QueueMessage.class);

        q = restrict(queueTaskQueryParam, matchTask, q);
        if (queueTaskQueryParam.getOrderBy() != null)
            q.orderBy(matchTask.queueMessageOrder(queueTaskQueryParam.getOrderBy(), queueMsg));

        return q.select(queueMsg);
    }

    public int deleteTasks(
            TaskQueryParam queueTaskQueryParam, int deleteTasksFetchSize) {
        Iterator<QueueMessage> queueMsgs = listQueueMessages(queueTaskQueryParam, 0, 0, deleteTasksFetchSize);
        int count = 0;
        while (queueMsgs.hasNext()) {
            deleteTask(queueMsgs.next());
            count++;
        }
        return count;
    }
}