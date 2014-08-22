package org.lancoder.master;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.LinkedBlockingDeque;

import org.apache.commons.io.FilenameUtils;
import org.lancoder.common.FFmpegProber;
import org.lancoder.common.RunnableService;
import org.lancoder.common.codecs.Codec;
import org.lancoder.common.file_components.FileInfo;
import org.lancoder.common.job.FFmpegPreset;
import org.lancoder.common.job.Job;
import org.lancoder.common.job.RateControlType;
import org.lancoder.common.network.messages.api.ApiJobRequest;
import org.lancoder.common.task.audio.AudioTaskConfig;
import org.lancoder.common.task.video.VideoTaskConfig;
import org.lancoder.common.utils.FileUtils;

public class JobInitiator extends RunnableService {

	private LinkedBlockingDeque<ApiJobRequest> requests = new LinkedBlockingDeque<>();
	private JobInitiatorListener listener;
	private MasterConfig config;

	public JobInitiator(JobInitiatorListener listener, MasterConfig config) {
		this.listener = listener;
		this.config = config;
	}

	public void process(ApiJobRequest request) {
		this.requests.add(request);
	}

	private void createJob(ApiJobRequest req, File sourceFile, String jobName) {
		System.out.println("Creating job for file " + sourceFile); // DEBUG
		// Get meta-data from source file
		File absoluteFile = FileUtils.getFile(config.getAbsoluteSharedFolder(), sourceFile.getPath());
		FileInfo fileInfo = FFmpegProber.getFileInfo(absoluteFile);

		FFmpegPreset preset = req.getPreset();
		RateControlType rateControlType = req.getRateControlType();

		// Limit to max pass from the rate control
		int passes = (req.getPasses() <= rateControlType.getMaxPass() ? req.getPasses() : rateControlType.getMaxPass());
		if (passes <= 0) {
			passes = 1;
		}
		int lengthOfTasks = 1000 * 60 * 5; // TODO get length of task (maybe in an 'advanced section')
		ArrayList<String> extraArgs = new ArrayList<>(); // TODO get extra encoder args from api request

		VideoTaskConfig vconfig = new VideoTaskConfig(sourceFile.getPath(), rateControlType, req.getRate(), passes,
				extraArgs, preset);
		AudioTaskConfig aconfig = new AudioTaskConfig(sourceFile.getPath(), RateControlType.CRF, 3, extraArgs,
				Codec.VORBIS, 2, 48000);
		Job job = new Job(jobName, sourceFile.getPath(), lengthOfTasks, config.getFinalEncodingFolder(), fileInfo,
				vconfig, aconfig);
		prepareFileSystem(job);
		listener.newJob(job);
	}

	private void createJob(ApiJobRequest req, File sourcefile) {
		createJob(req, sourcefile, req.getName());
	}

	private void processBatchRequest(ApiJobRequest req) {
		System.out.println("Directory given");
		File absoluteFolder = new File(new File(config.getAbsoluteSharedFolder()), req.getInputFile());
		Collection<File> toProcess = FileUtils.listFiles(absoluteFolder, new String[] { "mkv", "mp4", "avi", "mov" },
				true);
		for (File absoluteFile : toProcess) {
			String relativePath = new File(config.getAbsoluteSharedFolder()).toURI().relativize(absoluteFile.toURI())
					.getPath();
			File relativeFile = new File(relativePath);
			String fileName = FilenameUtils.removeExtension(relativeFile.getName());
			String jobName = String.format("%s - %s", req.getName(), fileName);
			createJob(req, relativeFile, jobName);
		}
	}

	private void processJobRequest(ApiJobRequest req) {
		String relativeSourceFile = req.getInputFile();
		File absoluteSourceFile = new File(config.getAbsoluteSharedFolder(), relativeSourceFile);
		if (absoluteSourceFile.isDirectory()) {
			processBatchRequest(req);
		} else {
			createJob(req, new File(relativeSourceFile));
		}
	}

	private void prepareFileSystem(Job j) {
		// Create base folders
		File absoluteOutput = FileUtils.getFile(config.getAbsoluteSharedFolder(), j.getOutputFolder());
		File absolutePartsOutput = FileUtils.getFile(absoluteOutput, j.getPartsFolderName());
		if (absoluteOutput.exists()) {
			try {
				// Attempt to clean
				System.err.printf("Directory is not empty. Attempting to clean %s\n", absoluteOutput.toString());
				FileUtils.cleanDirectory(absoluteOutput);
			} catch (IOException e) {
				e.printStackTrace();
			}
		} else {
			absoluteOutput.mkdirs();
		}
		FileUtils.givePerms(absoluteOutput, false);
		absolutePartsOutput.mkdir();
		FileUtils.givePerms(absolutePartsOutput, false);
	}

	@Override
	public void run() {
		try {
			while (!close) {
				processJobRequest(requests.take());
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	@Override
	public void serviceFailure(Exception e) {
		// TODO Auto-generated method stub
	}
}
