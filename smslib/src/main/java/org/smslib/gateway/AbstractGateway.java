
package org.smslib.gateway;

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;
import javax.xml.parsers.ParserConfigurationException;
import org.smslib.Service;
import org.smslib.core.Capabilities;
import org.smslib.core.Coverage;
import org.smslib.core.CreditBalance;
import org.smslib.core.Statistics;
import org.smslib.helper.Log;
import org.smslib.message.DeliveryReportMessage.DeliveryStatus;
import org.smslib.message.InboundMessage;
import org.smslib.message.MsIsdn;
import org.smslib.message.OutboundMessage;
import org.smslib.queue.DefaultOutboundQueue;
import org.smslib.queue.IOutboundQueue;
import org.smslib.threading.GatewayMessageDispatcher;
import org.xml.sax.SAXException;

public abstract class AbstractGateway
{
	public enum Status
	{
		Starting, Started, Stopping, Stopped, Error
	}

	protected String operatorId = "";

	Status status = Status.Stopped;

	String gatewayId = "";

	MsIsdn senderId = new MsIsdn();

	String description = "";

	int priority = 0;

	int maxMessageParts = 1;

	boolean requestDeliveryReport = false;

	Capabilities capabilities = new Capabilities();

	CreditBalance creditBalance = new CreditBalance();

	Statistics statistics = new Statistics();

	Object _LOCK_ = new Object();

	Semaphore concurrency = null;

	IOutboundQueue<OutboundMessage> messageQueue = new DefaultOutboundQueue();

	GatewayMessageDispatcher[] gatewayMessageDispatchers;

	int multipartReferenceNo = 0;

	public AbstractGateway(int noOfDispatchers, int concurrencyLevel, String id, String description)
	{
		this.gatewayMessageDispatchers = new GatewayMessageDispatcher[noOfDispatchers];
		this.concurrency = new Semaphore(concurrencyLevel, true);
		setGatewayId(id);
		setDescription(description);
	}

	public AbstractGateway(int concurrencyLevel, String id, String description)
	{
		this.gatewayMessageDispatchers = new GatewayMessageDispatcher[concurrencyLevel - 1];
		this.concurrency = new Semaphore(concurrencyLevel, true);
		setGatewayId(id);
		setDescription(description);
	}

	public Status getStatus()
	{
		return this.status;
	}

	public String getGatewayId()
	{
		return this.gatewayId;
	}

	public void setGatewayId(String gatewayId)
	{
		this.gatewayId = gatewayId;
	}

	public MsIsdn getSenderId()
	{
		return this.senderId;
	}

	public void setSenderId(MsIsdn senderId)
	{
		this.senderId = senderId;
	}

	public String getDescription()
	{
		return this.description;
	}

	public void setDescription(String description)
	{
		this.description = description;
	}

	public int getPriority()
	{
		return this.priority;
	}

	public void setPriority(int priority)
	{
		this.priority = priority;
	}

	public int getMaxMessageParts()
	{
		return this.maxMessageParts;
	}

	public void setMaxMessageParts(int n)
	{
		this.maxMessageParts = n;
	}

	public boolean getRequestDeliveryReport()
	{
		return this.requestDeliveryReport;
	}

	public void setRequestDeliveryReport(boolean requestDeliveryReport)
	{
		this.requestDeliveryReport = requestDeliveryReport;
	}

	public Capabilities getCapabilities()
	{
		return this.capabilities;
	}

	public void setCapabilities(Capabilities capabilities)
	{
		this.capabilities = capabilities;
	}

	public CreditBalance getCreditBalance()
	{
		return this.creditBalance;
	}

	public Statistics getStatistics()
	{
		return this.statistics;
	}

	final public boolean start()
	{
		synchronized (this._LOCK_)
		{
			if ((getStatus() == Status.Stopped) || (getStatus() == Status.Error))
			{
				try
				{
					setStatus(Status.Starting);
					Log.getInstance().getLog().info(String.format("Starting gateway: %s", toShortString()));
					getMessageQueue().start();
					_start();
					for (int i = 0; i < this.gatewayMessageDispatchers.length; i++)
					{
						this.gatewayMessageDispatchers[i] = new GatewayMessageDispatcher(String.format("Gateway Dispatcher %d [%s]", i, getGatewayId()), getMessageQueue(), this);
						this.gatewayMessageDispatchers[i].start();
					}
					setStatus(Status.Started);
				}
				catch (Exception e)
				{
					Log.getInstance().getLog().error("Unhandled Exception!", e);
					try
					{
						stop();
					}
					catch (Exception e1)
					{
						Log.getInstance().getLog().error("Unhandled Exception!", e1);
					}
					setStatus(Status.Error);
				}
			}
		}
		return (getStatus() == Status.Started);
	}

	final public boolean stop()
	{
		synchronized (this._LOCK_)
		{
			if ((getStatus() == Status.Started) || (getStatus() == Status.Error))
			{
				try
				{
					setStatus(Status.Stopping);
					Log.getInstance().getLog().info(String.format("Stopping gateway: %s", toShortString()));
					for (int i = 0; i < this.gatewayMessageDispatchers.length; i++)
					{
						if (this.gatewayMessageDispatchers[i] != null)
						{
							this.gatewayMessageDispatchers[i].cancel();
							this.gatewayMessageDispatchers[i].join();
						}
					}
					while (true)
					{
						OutboundMessage message = getMessageQueue().get();
						if (message == null) break;
						Service.getInstance().queue(message);
					}
					setStatus(Status.Stopped);
					getMessageQueue().stop();
					_stop();
				}
				catch (Exception e)
				{
					Log.getInstance().getLog().error("Unhandled Exception!", e);
					setStatus(Status.Error);
				}
			}
		}
		return (getStatus() == Status.Stopped);
	}

	final public boolean send(OutboundMessage message) throws InterruptedException, IOException, ParserConfigurationException, SAXException, TimeoutException
	{
		boolean acquiredLock = false;
		try
		{
			if (getStatus() != Status.Started)
			{
				Log.getInstance().getLog().warn("Outbound message routed via non-started gateway: " + message.toShortString() + " (" + getStatus() + ")");
				return false;
			}
			this.concurrency.acquire();
			acquiredLock = true;
			boolean result = _send(message);
			if (result)
			{
				getStatistics().increaseTotalSent();
				Service.getInstance().getStatistics().increaseTotalSent();
			}
			else
			{
				getStatistics().increaseTotalFailed();
				Service.getInstance().getStatistics().increaseTotalFailed();
			}
			return result;
		}
		catch (InterruptedException e)
		{
			getStatistics().increaseTotalFailures();
			Service.getInstance().getStatistics().increaseTotalFailures();
			throw e;
		}
		catch (IOException e)
		{
			getStatistics().increaseTotalFailures();
			Service.getInstance().getStatistics().increaseTotalFailures();
			throw e;
		}
		catch (ParserConfigurationException e)
		{
			getStatistics().increaseTotalFailures();
			Service.getInstance().getStatistics().increaseTotalFailures();
			throw e;
		}
		catch (SAXException e)
		{
			getStatistics().increaseTotalFailures();
			Service.getInstance().getStatistics().increaseTotalFailures();
			throw e;
		}
		catch (TimeoutException e)
		{
			getStatistics().increaseTotalFailures();
			Service.getInstance().getStatistics().increaseTotalFailures();
			throw e;
		}
		finally
		{
			if (acquiredLock) this.concurrency.release();
		}
	}

	final public boolean delete(InboundMessage message) throws InterruptedException, IOException, TimeoutException
	{
		boolean acquiredLock = false;
		try
		{
			if (getStatus() != Status.Started)
			{
				Log.getInstance().getLog().warn("Delete message via non-started gateway: " + message.toShortString() + " (" + getStatus() + ")");
				return false;
			}
			this.concurrency.acquire();
			acquiredLock = true;
			return _delete(message);
		}
		finally
		{
			if (acquiredLock) this.concurrency.release();
		}
	}

	final public DeliveryStatus queryDeliveryStatus(OutboundMessage message) throws InterruptedException, IOException, ParserConfigurationException, SAXException
	{
		boolean acquiredLock = false;
		try
		{
			this.concurrency.acquire();
			acquiredLock = true;
			return _queryDeliveryStatus(message.getOperatorMessageIds().get(0));
		}
		finally
		{
			if (acquiredLock) this.concurrency.release();
		}
	}

	final public DeliveryStatus queryDeliveryStatus(String operatorMessageId) throws InterruptedException, IOException, ParserConfigurationException, SAXException
	{
		boolean acquiredLock = false;
		try
		{
			this.concurrency.acquire();
			acquiredLock = true;
			return _queryDeliveryStatus(operatorMessageId);
		}
		finally
		{
			if (acquiredLock) this.concurrency.release();
		}
	}

	final public CreditBalance queryCreditBalance() throws InterruptedException, IOException, ParserConfigurationException, SAXException
	{
		boolean acquiredLock = false;
		try
		{
			this.concurrency.acquire();
			acquiredLock = true;
			return _queryCreditBalance();
		}
		finally
		{
			if (acquiredLock) this.concurrency.release();
		}
	}

	final public Coverage queryCoverage(Coverage coverage) throws InterruptedException, IOException, ParserConfigurationException, SAXException
	{
		boolean acquiredLock = false;
		try
		{
			this.concurrency.acquire();
			acquiredLock = true;
			return _queryCoverage(coverage);
		}
		finally
		{
			if (acquiredLock) this.concurrency.release();
		}
	}

	public boolean queue(OutboundMessage message) throws Exception
	{
		Log.getInstance().getLog().debug("Queue: " + message.toShortString());
		return getMessageQueue().add(message);
	}

	public int getQueueLoad() throws Exception
	{
		return getMessageQueue().size();
	}

	abstract protected void _start() throws IOException, TimeoutException, InterruptedException;

	abstract protected void _stop() throws IOException, TimeoutException, InterruptedException;

	abstract protected boolean _send(OutboundMessage message) throws IOException, ParserConfigurationException, SAXException, TimeoutException, NumberFormatException, InterruptedException;

	abstract protected boolean _delete(InboundMessage message) throws IOException, TimeoutException, NumberFormatException, InterruptedException;

	abstract protected DeliveryStatus _queryDeliveryStatus(String operatorMessageId) throws IOException, ParserConfigurationException, SAXException;

	abstract protected CreditBalance _queryCreditBalance() throws IOException, ParserConfigurationException, SAXException;

	abstract protected Coverage _queryCoverage(Coverage coverage) throws IOException, ParserConfigurationException, SAXException;

	private void setStatus(Status status)
	{
		Status oldStatus = this.status;
		this.status = status;
		Status newStatus = this.status;
		Service.getInstance().getCallbackManager().registerGatewayStatusEvent(this, oldStatus, newStatus);
	}

	protected IOutboundQueue<OutboundMessage> getMessageQueue()
	{
		return (IOutboundQueue<OutboundMessage>) this.messageQueue;
	}

	protected int GetNextMultipartReferenceNo()
	{
		if (this.multipartReferenceNo == 0)
		{
			this.multipartReferenceNo = new Random().nextInt();
			if (this.multipartReferenceNo < 0) this.multipartReferenceNo *= -1;
			this.multipartReferenceNo %= 65536;
		}
		this.multipartReferenceNo = (this.multipartReferenceNo + 1) % 65536;
		return this.multipartReferenceNo;
	}

	public String toShortString()
	{
		return String.format("%s (%s)", getGatewayId(), getDescription());
	}

	@Override
	public String toString()
	{
		StringBuffer b = new StringBuffer(1024);
		b.append("== GATEWAY ========================================================================\n");
		b.append(String.format("Gateway ID:  %s\n", getGatewayId()));
		b.append(String.format("Description: %s\n", getDescription()));
		b.append(String.format("Sender ID:   %s\n", getSenderId()));
		b.append(String.format("-- Capabilities --\n"));
		b.append(getCapabilities().toString());
		b.append(String.format("-- Settings --\n"));
		b.append(String.format("Request Delivery Reports: %b\n", getRequestDeliveryReport()));
		b.append("== GATEWAY END ========================================================================\n");
		return b.toString();
	}
}
