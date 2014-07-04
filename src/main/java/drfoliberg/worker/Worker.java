package main.java.drfoliberg.worker;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Enumeration;

import main.java.drfoliberg.common.ServerListener;
import main.java.drfoliberg.common.Service;
import main.java.drfoliberg.common.network.Routes;
import main.java.drfoliberg.common.network.messages.cluster.ConnectMessage;
import main.java.drfoliberg.common.network.messages.cluster.CrashReport;
import main.java.drfoliberg.common.network.messages.cluster.Message;
import main.java.drfoliberg.common.network.messages.cluster.StatusReport;
import main.java.drfoliberg.common.network.messages.cluster.TaskRequestMessage;
import main.java.drfoliberg.common.status.NodeState;
import main.java.drfoliberg.common.status.TaskState;
import main.java.drfoliberg.common.task.video.TaskReport;
import main.java.drfoliberg.common.task.video.VideoEncodingTask;
import main.java.drfoliberg.worker.server.WorkerHttpServer;
import main.java.drfoliberg.worker.server.WorkerServletListerner;

import org.apache.commons.io.Charsets;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;

import com.google.gson.Gson;

public class Worker implements Runnable, ServerListener, WorkerServletListerner, ConctactMasterListener {

	WorkerConfig config;

	private String configPath;
	private VideoEncodingTask currentTask;
	private NodeState status;
	private ArrayList<Service> services;
	private WorkerHttpServer server;
	private WorkThread workThread;
	private InetAddress address;

	public Worker(String configPath) {
		this.configPath = configPath;

		services = new ArrayList<>();

		config = WorkerConfig.load(configPath);
		if (config != null) {
			System.err.println("Loaded config from disk !");
		} else {
			// this saves default configuration to disk
			this.config = WorkerConfig.generate(configPath);
		}
		server = new WorkerHttpServer(config.getListenPort(), this, this);
		services.add(server);
		print("initialized not connected to a master server");

		try {

			Enumeration<NetworkInterface> n = NetworkInterface.getNetworkInterfaces();
			for (; n.hasMoreElements();) {
				NetworkInterface e = n.nextElement();
				Enumeration<InetAddress> a = e.getInetAddresses();
				for (; a.hasMoreElements();) {
					InetAddress addr = a.nextElement();
					if (!addr.isLoopbackAddress() && (addr  instanceof Inet4Address) ) {
						address = addr;
						System.out.println("Assuming worker ip is:" + address.getHostAddress());
					}

				}
			}
		} catch (SocketException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public Worker(WorkerConfig config) {
		this.config = config;
	}

	public void shutdown() {
		int nbServices = services.size();
		print("shutting down " + nbServices + " service(s).");

		for (Service s : services) {
			s.stop();
		}

		Socket socket = null;
		try {
			socket = new Socket(config.getMasterIpAddress(), config.getMasterPort());
			ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
			out.flush();
			ObjectInputStream in = new ObjectInputStream(socket.getInputStream());

			// Send a connect message with a status indicating disconnection
			Message message = new ConnectMessage(config.getUniqueID(), config.getListenPort(), config.getName(),
					NodeState.NOT_CONNECTED);
			out.writeObject(message);
			out.flush();
			Object o = in.readObject();
			if (o instanceof Message) {
				Message m = (Message) o;
				switch (m.getCode()) {
				case BYE:
					socket.close();
					break;
				default:
					socket.close();
					print("something odd happened");
					break;
				}
			} else {
				print("received invalid message!");
			}
			socket.close();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		}

		config.dump(configPath);
	}

	public void print(String s) {
		System.out.println((getWorkerName().toUpperCase()) + ": " + s);
	}

	public void taskDone(VideoEncodingTask t) {
		this.currentTask.setStatus(TaskState.TASK_COMPLETED);
		this.updateStatus(NodeState.FREE);
		services.remove(workThread);
	}

	public void stopWork(VideoEncodingTask t) {
		// TODO check which task to stop (if many tasks are implemented)
		this.workThread.stop();
		System.err.println("Setting current task to null");
		this.currentTask = null;
		this.updateStatus(NodeState.FREE);
	}

	public synchronized boolean startWork(VideoEncodingTask t) {
		if (this.getStatus() != NodeState.FREE) {
			print("cannot accept work as i'm not free. Current status: " + this.getStatus());
			return false;
		} else {
			this.currentTask = t;
			this.workThread = new WorkThread(this, t);
			Thread wt = new Thread(workThread);
			wt.start();
			services.add(workThread);
			return true;
		}
	}

	/**
	 * Get a status report of the worker.
	 * 
	 * @return the StatusReport object
	 */
	public StatusReport getStatusReport() {
		return new StatusReport(getStatus(), config.getUniqueID(), getTaskReport());
	}

	/**
	 * Get a task report of the current task.
	 * 
	 * @return null if no current task
	 */
	public TaskReport getTaskReport() {
		// if worker has no task, return null report
		TaskReport taskReport = null;
		if (currentTask != null) {
			taskReport = new TaskReport(config.getUniqueID(), this.currentTask);
			VideoEncodingTask t = taskReport.getTask();
			t.setTimeElapsed(System.currentTimeMillis() - currentTask.getTimeStarted());
			t.setTimeEstimated(currentTask.getETA());
			t.setProgress(currentTask.getProgress());
		}
		return taskReport;
	}

	public synchronized void updateStatus(NodeState statusCode) {

		if (this.status == NodeState.NOT_CONNECTED && statusCode != NodeState.NOT_CONNECTED) {
			this.stopContactMaster();
		}
		print("changing worker status to " + statusCode);
		this.status = statusCode;

		switch (statusCode) {
		case FREE:
			// notifyMasterStatusChange(statusCode);
			notifyHttpMasterStatusChange();
			this.currentTask = null;
			break;
		case WORKING:
		case PAUSED:
			notifyMasterStatusChange(statusCode);
			break;
		case NOT_CONNECTED:
			// start thread to try to contact master
			startContactMaster();
			break;
		case CRASHED:
			// cancel current work
			this.currentTask = null;
			break;
		default:
			System.err.println("WORKER: Unhandlded status code while" + " updating status");
			break;
		}
	}

	private void startContactMaster() {
		ContactMasterHttp contact = new ContactMasterHttp(getMasterIpAddress(), getMasterPort(), this);
		Thread mastercontactThread = new Thread(contact);
		mastercontactThread.start();
		this.services.add(contact);
	}

	public void stopContactMaster() {
		System.out.println("Trying to stop contact service");
		for (Service s : this.services) {
			if (s instanceof ContactMasterHttp) {
				System.out.println("Found service. Sending stop request.");
				s.stop();
				break;
			}
		}
	}

	public synchronized boolean sendCrashReport(CrashReport report) {
		try {
			System.err.println("Sending crash report");
			Socket s = new Socket(config.getMasterIpAddress(), config.getMasterPort());
			ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream());
			ObjectInputStream in = new ObjectInputStream(s.getInputStream());
			out.flush();
			out.writeObject(report);
			out.flush();
			in.close();
			s.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return true;
	}

	public boolean notifyHttpMasterStatusChange() {
		boolean success = false;
		CloseableHttpClient client = HttpClientBuilder.create().build();
		StatusReport report = this.getStatusReport();
		Gson gson = new Gson();
		try {
			StringEntity entity = new StringEntity(gson.toJson(report));
			entity.setContentEncoding(Charsets.UTF_8.toString());
			entity.setContentType(ContentType.APPLICATION_JSON.toString());
			URI url = new URI("http", null, config.getMasterIpAddress().getHostAddress(), config.getMasterPort(),
					Routes.NODE_STATUS, null, null);
			HttpPost post = new HttpPost(url);

			post.setEntity(entity);

			CloseableHttpResponse response = client.execute(post);
			if (response.getStatusLine().getStatusCode() == 200) {
				success = true;
			}else{
				System.err.println(response.getStatusLine().getStatusCode());
			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (URISyntaxException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return success;
	}

	@Deprecated
	public boolean notifyMasterStatusChange(NodeState status) {
		Socket socket = null;
		boolean success = true;
		try {
			// Init the socket to master
			socket = new Socket(getMasterIpAddress(), config.getMasterPort());
			ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
			out.flush();
			ObjectInputStream in = new ObjectInputStream(socket.getInputStream());

			// send report in socket
			out.writeObject(getStatusReport());
			out.flush();
			Object o = in.readObject();
			// check if master sent node new UNID
			if (o instanceof Message) {
				Message response = (Message) o;
				switch (response.getCode()) {
				case BYE:
					// master is closing the socket
					break;
				default:
					System.err.println("WORKER:" + " Master sent unexpected message response");
				}
			} else {
				System.err.println("WORKER CONTACT:" + " Could not read what master sent !");
			}
			socket.close();
		} catch (IOException e) {
			e.printStackTrace();
			success = false;
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
			success = false;
		} finally {
			if (socket != null) {
				try {
					socket.close();
				} catch (IOException e) {
					// pls java
				}
			}
		}
		return success;
	}

	public int getListenPort() {
		return config.getListenPort();
	}

	public InetAddress getMasterIpAddress() {
		return config.getMasterIpAddress();
	}

	public int getMasterPort() {
		return config.getMasterPort();
	}

	public NodeState getStatus() {
		return this.status;
	}

	public String getWorkerName() {
		return config.getName();
	}

	public void run() {
		for (Service s : services) {
			Thread t = new Thread(s);
			t.start();
		}
		updateStatus(NodeState.NOT_CONNECTED);
		System.err.println("Started all services");
	}

	public void setUnid(String unid) {
		print("got id " + unid + " from master");
		this.config.setUniqueID(unid);
		this.config.dump(configPath);
	}

	public VideoEncodingTask getCurrentTask() {
		return this.currentTask;
	}

	@Override
	public boolean taskRequest(TaskRequestMessage tqm) {
		return startWork(tqm.task);
	}

	@Override
	public StatusReport statusRequest() {
		return getStatusReport();
	}

	@Override
	public void serverShutdown(Service server) {
		this.services.remove(server);
	}

	@Override
	public void serverFailure(Exception e, Service server) {
		e.printStackTrace();
	}

	@Override
	public boolean deleteTask(TaskRequestMessage tqm) {
		if (tqm != null && currentTask != null && tqm.task.equals(currentTask)) {
			this.stopWork(currentTask);
			return true;
		}
		return false;
	}

	@Override
	public void shutdownWorker() {
		System.err.println("Received shutdown request from api !");
		this.shutdown();
	}

	@Override
	public void receivedUnid(String unid) {
		setUnid(unid);
		updateStatus(NodeState.FREE);
	}

	@Override
	public String getCurrentNodeUnid() {
		return this.config.getUniqueID();
	}

	@Override
	public String getCurrentNodeName() {
		return this.getWorkerName();
	}

	@Override
	public int getCurrentNodePort() {
		return this.getListenPort();
	}

	@Override
	public InetAddress getCurrentNodeAddress() {
		return this.address;
	}
}