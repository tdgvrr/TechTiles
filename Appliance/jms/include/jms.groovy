//
// JMS utilities for the TechTiles projects
//
// Copyright (c) TechTiles, LLC - All Rights Reserved
//
// 2016-Jan-04: New [VRR]
// 2021-Feb-15: @CompileStatic and JAR creation
//

import org.apache.activemq.ActiveMQConnectionFactory
import org.apache.activemq.* 

import javax.jms.Connection
import javax.jms.DeliveryMode
import javax.jms.Destination
import javax.jms.ExceptionListener
import javax.jms.JMSException
import javax.jms.Message
import javax.jms.MessageConsumer
import javax.jms.MessageProducer
import javax.jms.Session
import javax.jms.TextMessage
import javax.jms.MapMessage
import javax.jms.*
 
import java.text.*
import groovy.text.*
import java.util.regex.*
import java.util.*
import groovy.io.*
import groovy.*

import org.apache.log4j.*
import groovy.util.logging.Log4j
import groovy.transform.*

import static Constants.*

class Constants {
  static final String BROKER_HOST = "tcp://10.8.0.1:61616?wireFormat.maxInactivityDuration=600000&keepAlive=true&useKeepAlive=true"
                          // "stomp://localhost:61614"
                          // "ws://localhost:61614"
  static final String BROKER_USER = "FMappliance"
  static final String BROKER_PASS = "FMapassword"
}

@CompileStatic
def JMSinitProducer(String qname)
{ 
 try 
 {
   ActiveMQConnectionFactory connectionFactory = new ActiveMQConnectionFactory(BROKER_USER, BROKER_PASS, BROKER_HOST)
   Connection connection = connectionFactory.createConnection()
   connection.start()
   Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)
   if (qname != null)
   {
      Destination destination = session.createQueue(qname)
      MessageProducer producer = session.createProducer(destination)
      producer.setDeliveryMode(DeliveryMode.PERSISTENT)
      return [connection, session, producer]
   }
   MessageProducer producer = session.createProducer(null)
   producer.setDeliveryMode(DeliveryMode.PERSISTENT)
   return [connection, session, producer]
 }
 catch (Exception e)
 {
   println "***Exception in JMSinitProducer***"
   println e
   return [null, null, null]
 }
}

// If a connection is passed, we setup the session on an existing connection 

@CompileStatic
def JMSinitProducer(String qname, Connection connection)
{ 
 try 
 {
   if (!connection)
   {
      println "***Error in JmsInitProducer - missing connection"
      return [null, null, null]
   }

   Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)
   if (qname != null)
   {
      Destination destination = session.createQueue(qname)
      MessageProducer producer = session.createProducer(destination)
      producer.setDeliveryMode(DeliveryMode.PERSISTENT)
      return [connection, session, producer]
   }
   MessageProducer producer = session.createProducer(null)
   producer.setDeliveryMode(DeliveryMode.PERSISTENT)
   return [connection, session, producer]
 }
 catch (Exception e)
 {
   println "***Exception in JMSinitProducer***"
   println e
   return [null, null, null]
 }
}

@CompileStatic
def JMSsendTo(Destination destination, Session session, MessageProducer producer, Object text)
{
 try
 {
   if (text.getClass().toString().contains("String"))
   {
      TextMessage message = session.createTextMessage((String)text)
      producer.send(destination, message)
   }
   else
   {
      if ((text instanceof Serializable) || (text instanceof Externalizable))
      {
         ByteArrayOutputStream bos = new ByteArrayOutputStream()
         ObjectOutput out = new ObjectOutputStream(bos)
         out.writeObject(text);
         byte[] textBytes = bos.toByteArray()
         out.close()
         bos.close()
         BytesMessage message = session.createBytesMessage()
         message.writeBytes(textBytes)
         producer.send(destination, message)
     }
     else
     {
         println "ERROR: Object class " + text.getClass() + " is not serializable - don't know how to transmit"
         return false
     }
   }
   return true
 }
 catch (Exception e)
 {
   println "***Exception in JMSsendTo***"
   println e
   return false
 }
}

@CompileStatic
int JMSsend(Session session, MessageProducer producer, Object text) 
{
 try
 {
   if (text.getClass().toString().contains("String"))
   {
      TextMessage message = session.createTextMessage((String)text)
      producer.send(message)
   }
   else
   { 
      if ((text instanceof Serializable) || (text instanceof Externalizable))
      { 
         ByteArrayOutputStream bos = new ByteArrayOutputStream()
         ObjectOutput out = new ObjectOutputStream(bos)   
         out.writeObject(text);
         byte[] textBytes = bos.toByteArray()
         out.close()
         bos.close()
         BytesMessage message = session.createBytesMessage() 
         message.writeBytes(textBytes)
         producer.send(message)
     }
     else
     {
         println "ERROR: Object class " + text.getClass() + " is not serializable - don't know how to transmit"
         return false
     }
   }
   return true
 }
 catch (Exception e)
 {
   println "***Exception in JMSsend***"
   println e
   return false
 }
}

@CompileStatic 
def JMSsendWithReplyTo(Session session, MessageProducer producer, Object text, Destination replyq, long ttl)
{
 try
 {
   if (text.getClass().toString().contains("String"))
   {
      TextMessage message = session.createTextMessage((String)text)
      if (replyq)
         message.setJMSReplyTo(replyq)
      producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT)
      if (ttl)
      {
         def current_ttl = producer.getTimeToLive()
         def current_dm  = producer.getDeliveryMode()
         producer.setTimeToLive(ttl)
         producer.send(message)
         producer.setTimeToLive(current_ttl)
         producer.setDeliveryMode(current_dm)
      }
      else
         producer.send(message)
    
   }
   else
   {
      if ((text instanceof Serializable) || (text instanceof Externalizable))
      {
         ByteArrayOutputStream bos = new ByteArrayOutputStream()
         ObjectOutput out = new ObjectOutputStream(bos)
         out.writeObject(text);
         byte[] textBytes = bos.toByteArray()
         out.close()
         bos.close()
         BytesMessage message = session.createBytesMessage()
         message.writeBytes(textBytes)
         if (replyq)
            message.setJMSReplyTo((Destination)replyq)
         if (ttl)
         {
            def current_ttl = producer.getTimeToLive()
            def current_dm  = producer.getDeliveryMode()
            producer.setTimeToLive(ttl)
            producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT)
            producer.send(message)
            producer.setTimeToLive(current_ttl)
            producer.setDeliveryMode(current_dm)
         }
         else
            producer.send(message)
     }
     else
     {
         println "ERROR: Object class " + text.getClass() + " is not serializable - don't know how to transmit"
         return false
     }
   }
   return true
 }
 catch (Exception e)
 {
   println "***Exception in JMSsend***"
   println e
   return false
 }
}

def JMSterm(Connection connection, Session session, MessageConsumer consumer)
{ 
 try
 {              
    if (consumer)
    {
       consumer.stop()
       consumer.acknowledge()
       consumer.close()
    }
    session.close()
    connection.close()
    return true
 }
 catch (Exception e) 
 {
    println "***Exception in JMSterm***"
    println e
    return false
 }
}

@CompileStatic
def JMSinitTempConsumer()
{
 try
 {
  ActiveMQConnectionFactory connectionFactory = new ActiveMQConnectionFactory(BROKER_USER, BROKER_PASS, BROKER_HOST)
  Connection connection = connectionFactory.createConnection()
  connection.start()
  Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)
  TemporaryQueue tempq = session.createTemporaryQueue()
  // Destination destination = session.getQueue(tempq.toString())
  MessageConsumer consumer = session.createConsumer(tempq)
  return [connection, session, consumer, tempq] 
 }
 catch (Exception e)
 {
    println "***Exception in JMSinitTempConsumer***"
    println e
    return [null, null, null, null]
 }
}
 
def JMSinitConsumer(qname)
{
 try 
 {
  ActiveMQConnectionFactory connectionFactory = new ActiveMQConnectionFactory(BROKER_USER, BROKER_PASS, BROKER_HOST)
  Connection connection = connectionFactory.createConnection()
  connection.start()
  Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)
  Destination destination = session.createQueue(qname)
  MessageConsumer consumer = session.createConsumer(destination)
  return [connection, session, consumer, destination] 
 }
 catch (Exception e)
 {
    println "***Exception in JMSinitConsumer***"
    println e
    return [null, null, null, null]
 } 
}

@CompileStatic
def JMSrecv(MessageConsumer consumer, long timeout)
{
 try
 {
    Message message = consumer.receive(timeout)
    if (message instanceof TextMessage) 
    {
       TextMessage textMessage = (TextMessage) message
       String text = textMessage.getText()
       return text
    } 
    return message
 }
 catch (Exception e)
 {
    println "***Exception in JMSrecv***"
    println e
    return null
 }
}

@CompileStatic
def JMSrecvWithReplyTo(MessageConsumer consumer, long timeout)
{
def timestamp = 0

 try
 {
    Message message = consumer.receive(timeout)
    
//  if (message && message?.brokerInTime)
//     timestamp = message?.brokerInTime

    if (message && message?.JMSTimestamp)
       timestamp = message?.JMSTimestamp
    if (message instanceof TextMessage)
    {
       TextMessage textMessage = (TextMessage) message
       String text = textMessage.getText()
       return [text, message.getJMSReplyTo(), timestamp]
    }
    if (message)
       return [message, message.getJMSReplyTo(), timestamp]
    else
       return [message, null, timestamp]
 }
 catch (Exception e)
 {
    println "***Exception in JMSrecvWithReplyTo***"
    println e
    return [null, null, 0]
 }
}

