package org.lancoder.worker.converter.video;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.lancoder.common.codecs.Codec;
import org.lancoder.common.config.Config;
import org.lancoder.common.exceptions.MissingDecoderException;
import org.lancoder.common.exceptions.MissingThirdPartyException;
import org.lancoder.common.file_components.streams.VideoStream;
import org.lancoder.common.pool.PoolListener;
import org.lancoder.common.task.video.ClientVideoTask;
import org.lancoder.common.utils.FileUtils;
import org.lancoder.common.utils.TimeUtils;
import org.lancoder.ffmpeg.FFmpegReader;
import org.lancoder.worker.converter.Converter;

public class VideoWorkThread extends Converter<ClientVideoTask> {

	private static String OS = System.getProperty("os.name").toLowerCase();

	private static Pattern currentFramePattern = Pattern.compile("frame=\\s+([0-9]+)");
	private static Pattern missingDecoder = Pattern.compile("Error while opening encoder for output stream");

	private FFmpegReader ffmpeg = new FFmpegReader();
	private Transcoder transcoder = new Transcoder();

	public VideoWorkThread(PoolListener<ClientVideoTask> listener, String absoluteSharedFolder,
			String tempEncodingFolder, Config config) {
		super(listener, absoluteSharedFolder, tempEncodingFolder, config);
	}

	@Override
	public void stop() {
		super.stop();
		ffmpeg.stop();
		transcoder.stop();
	}

	private static boolean isWindows() {
		return (OS.indexOf("win") >= 0);
	}

	public void encodePass(String startTimeStr, String durationStr) throws MissingDecoderException,
			MissingThirdPartyException {
		VideoStream inStream = task.getStreamConfig().getOrignalStream();
		VideoStream outStream = task.getStreamConfig().getOutStream();
		String encodingLibrary = outStream.getCodec().getEncoder();
		File inputFile = new File(absoluteSharedDir, inStream.getRelativeFile());
		String mapping = String.format("0:%d", inStream.getIndex());
		// Get parameters from the task and bind parameters to process
		String[] baseArgs = new String[] { config.getFFmpegPath(), "-ss", startTimeStr, "-t", durationStr, "-i",
				inputFile.getAbsolutePath(), "-sn", "-force_key_frames", "0", "-an", "-map", mapping, "-c:v",
				encodingLibrary };
		ArrayList<String> ffmpegArgs = new ArrayList<>();
		// Add base args to process builder
		Collections.addAll(ffmpegArgs, baseArgs);

		ffmpegArgs.addAll(task.getRateControlArgs());
		ffmpegArgs.addAll(task.getPresetArg());

		// output file and pass arguments
		String outFile = taskTempOutputFile.getAbsoluteFile().toString();
		if (task.getStepCount() > 1) {
			// Add pass arguments
			ffmpegArgs.add("-pass");
			ffmpegArgs.add(String.valueOf(task.getProgress().getCurrentStepIndex()));
			if (task.getProgress().getCurrentStepIndex() != task.getStepCount()) {
				ffmpegArgs.add("-f");
				ffmpegArgs.add("rawvideo");
				ffmpegArgs.add("-y");
				// Change output file to null
				outFile = isWindows() ? "NUL" : "/dev/null";
			}
		}
		ffmpegArgs.add(outFile);
		ffmpeg = new FFmpegReader();
		// Start process in task output directory (log and mtrees pass files generated by ffmpeg)
		ffmpeg.read(ffmpegArgs, this, true, taskTempOutputFolder);
	}

	private boolean transcodeToMpegTs() {
		if (taskFinalFile.exists()) {
			System.err.printf("Cannot transcode to mkv as file %s already exists\n", taskFinalFile.getPath());
			return false;
		}
		String[] baseArgs = new String[] { config.getFFmpegPath(), "-i", taskTempOutputFile.getAbsolutePath(), "-f",
				"mpegts", "-c", "copy", "-bsf:v", "h264_mp4toannexb", taskFinalFile.getAbsolutePath() };
		ArrayList<String> args = new ArrayList<>();
		Collections.addAll(args, baseArgs);
		try {
			transcoder = new Transcoder();
			transcoder.read(args);
			FileUtils.givePerms(taskFinalFile, false);
		} catch (MissingDecoderException e) {
			e.printStackTrace();
		} catch (MissingThirdPartyException e) {
			e.printStackTrace();
		}
		return true;
	}

	@Override
	public void serviceFailure(Exception e) {
		this.listener.crash(e);
	}

	@Override
	public void onMessage(String line) {
		Matcher m = currentFramePattern.matcher(line);
		if (m.find()) {
			long units = Long.parseLong(m.group(1));
			task.getProgress().update(units);
		}
		m = missingDecoder.matcher(line);
		if (m.find()) {
			System.err.println("Missing decoder !");
			listener.crash(new MissingDecoderException("Missing decoder or encoder"));
		}
	}

	@Override
	protected void start() {
		boolean success = false;
		try {
			listener.started(task);
			setFiles();
			createDirs();
			// use start and duration for ffmpeg legacy support
			long durationMs = task.getEncodingEndTime() - task.getEncodingStartTime();
			String startTimeStr = TimeUtils.getStringFromMs(task.getEncodingStartTime());
			String durationStr = TimeUtils.getStringFromMs(durationMs);

			int currentStep = 1;
			while (currentStep <= task.getStepCount()) {
				System.err.printf("Encoding pass %d of %d\n", task.getProgress().getCurrentStepIndex(),
						task.getStepCount());
				encodePass(startTimeStr, durationStr);
				task.getProgress().completeStep();
				currentStep++;
			}
			// TODO move to a codec strategy
			if (task.getStreamConfig().getOutStream().getCodec() == Codec.H264) {
				success = transcodeToMpegTs();
			} else {
				if (taskFinalFile.exists()) {
					System.err.printf("Cannot transcode to mkv as file %s already exists\n", taskFinalFile.getPath());
					success = false;
				}
				try {
					FileUtils.moveFile(taskTempOutputFile, taskFinalFile);
					FileUtils.givePerms(taskFinalFile, false);
					success = true;
				} catch (IOException e) {
					e.printStackTrace();
					success = false;
				}
			}
		} catch (MissingThirdPartyException | MissingDecoderException e) {
			listener.crash(e);
		} finally {
			if (success) {
				listener.completed(task);
			} else {
				listener.failed(task);
			}
		}
		this.destroyTempFolder();
	}
}
