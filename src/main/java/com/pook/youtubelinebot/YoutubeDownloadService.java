package com.pook.youtubelinebot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import ytgrabber.DownloadLink;
import ytgrabber.DownloadResource;
import ytgrabber.YTWrapper;

import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Created by pook on 6/11/2017.
 */
@Service
public class YoutubeDownloadService {
    private static final Logger logger = LoggerFactory.getLogger(YoutubeDownloadService.class);

    AmazonS3Service amazonS3Service;

    public static void main(String... args) throws IOException, InterruptedException {
        AmazonS3Service amazonS3Service = new AmazonS3Service();
        YoutubeDownloadService s = new YoutubeDownloadService(amazonS3Service);
        s.getLinkFromVideo("https://youtu.be/1guOQX6_UPo");
    }

    @Autowired
    public YoutubeDownloadService(AmazonS3Service amazonS3Service) {
        this.amazonS3Service = amazonS3Service;
    }

    public String getLinkFromVideo(String youtubeLink) throws IOException, InterruptedException {
        String mp3FileName = getMp3FileName(youtubeLink);
        logger.info("MP3 file name: " + mp3FileName);
        Path downloadedContentDir = YoutubeLineBotApplication.downloadedContentDir != null ? YoutubeLineBotApplication.downloadedContentDir : Files.createTempDirectory("downloadedVideo");
        String command = getBaseYoutubeDlCommand(youtubeLink, downloadedContentDir.toString());
        logger.info("Running process: [{}]", command);
        final Process p = Runtime.getRuntime().exec(command);
        BufferedReader input = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String line = null;
        try {
            while ((line = input.readLine()) != null) {
                logger.info("FFMPEG: {}", line);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        printError(p);
        p.waitFor();

        String mp3Link = uploadFileToS3(downloadedContentDir.toString() + "/" + mp3FileName, mp3FileName);
        return mp3Link;
    }

    private void printError(Process p) {
        BufferedReader input = new BufferedReader(new InputStreamReader(p.getErrorStream()));
        String line = null;
        try {
            while ((line = input.readLine()) != null) {
                logger.info("ERROR: {}", line);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String getMp3FileName(String youtubeLink) throws IOException, InterruptedException {
        String command = getYoutubeDlFileNameCommand(youtubeLink);
        logger.info("Running process: [{}]", command);
        final Process p = Runtime.getRuntime().exec(command);
        p.waitFor();  // wait for process to finish then continue.

        BufferedReader bri = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String videoFileName = "";
        String output;
        while ((output = bri.readLine()) != null) {
            videoFileName += output;
        }
        String[] nameParts = videoFileName.split("\\.");
        return String.join(".", Arrays.copyOfRange(nameParts, 0, nameParts.length - 1)) + ".mp3";
    }

    private String getYoutubeDlFileNameCommand(String youtubeLink) {
        return getBaseYoutubeDlCommand(youtubeLink, "") + " --get-filename";
    }

    private String getBaseYoutubeDlCommand(String youtubeLink, String downloadPath) {
        String outputFormat = "%(title)s.%(ext)s";
        String command = "youtube-dl " + youtubeLink + " -f bestaudio --extract-audio --audio-format mp3 --audio-quality 0 ";
        if (StringUtils.isEmpty(downloadPath)) {
            command += "-o " + outputFormat;
        } else {
            command += "-o " + downloadPath + "/" + outputFormat;
        }
        return command;
    }

    public String getMp3LinkFromVideo(String youtubeLink) throws IOException {
        DownloadResource downloadResource = YTWrapper.extractLinks(youtubeLink);
        if (downloadResource.getError() != null) {
            logger.error("Unable to get Youtube video download link: " + downloadResource.getError());
            throw new RuntimeException("Unable to get Youtube video download link: " + downloadResource.getError());
        }

        Path downloadedContentDir = YoutubeLineBotApplication.downloadedContentDir != null ? YoutubeLineBotApplication.downloadedContentDir : Files.createTempDirectory("downloadedVideo");
        DownloadLink downloadUrl = getBestDownloadResource(downloadResource);

        Path downloadPath = downloadedContentDir.resolve(downloadResource.getTitle() + "." + downloadUrl.getType().getFormat());
        downloadFromUrl(new URL(downloadUrl.getUrl()), downloadPath.toString());

        String mp3FileName = downloadResource.getTitle() + "." + "mp3";
        Path convertedMp3Path = downloadedContentDir.resolve(mp3FileName);
        convertVidToMp3(downloadPath.toString(), convertedMp3Path.toString());

        String mp3DownloadLink = uploadFileToS3(convertedMp3Path.toString(), mp3FileName);
        return mp3DownloadLink;
    }

    private DownloadLink getBestDownloadResource(DownloadResource downloadResource) {
        return downloadResource.getLinks().stream().findFirst().filter(link -> link.getType().getFormat().equals("mp4")).orElseGet(() -> downloadResource.getLinks().get(0));
    }

    private String uploadFileToS3(String localPath, String filename) {
        InputStream inputSteam = null;
        try {
            inputSteam = new FileInputStream(localPath);
            return amazonS3Service.uploadMp3(filename, inputSteam);
        } catch (Exception e) {
            logger.error("Error occurred while downloading file or upload file", e);
            throw new RuntimeException(e);
        } finally {
            try {
                inputSteam.close();
            } catch (IOException e) {
                logger.debug("Fali to close stream?: ", e);
            }
        }
    }

    private void convertVidToMp3(String videoPath, String mp3Path) throws IOException {
        ProcessBuilder builder = new ProcessBuilder("ffmpeg", "-i", videoPath, "-codec:a", "libmp3lame", "-q:a", "0", "-map", "a", mp3Path);
        logger.info("Running process: [{}]", builder.command());
        builder.redirectErrorStream(true);
        final Process process = builder.start();
        BufferedReader input = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String line = null;
        try {
            while ((line = input.readLine()) != null) {
                logger.info("FFMPEG: {}", line);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Watch the process
        //watch(process);
    }

    void downloadFromUrl(URL url, String localFilename) throws IOException {
        logger.info("Downloading to [{}] from URL [{}]", localFilename, url);
        InputStream is = null;
        FileOutputStream fos = null;

        try {
            URLConnection urlConn = url.openConnection();//connect

            is = urlConn.getInputStream();               //get connection inputstream
            fos = new FileOutputStream(localFilename);   //open outputstream to local file

            byte[] buffer = new byte[4096];              //declare 4KB buffer
            int len;

            //while we have availble data, continue downloading and storing to local file
            while ((len = is.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }
        } catch (Exception e) {
            logger.error("Error: ", e);
            throw new RuntimeException(e);
        } finally {
            try {
                if (is != null) {
                    is.close();
                }
            } finally {
                if (fos != null) {
                    fos.close();
                }
            }
        }
    }

    private static void watch(final Process process) {
        new Thread() {
            public void run() {
                logger.info("Running process: []", process);
                BufferedReader input = new BufferedReader(new InputStreamReader(process.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line = null;
                try {
                    while ((line = input.readLine()) != null) {
                        logger.info("Process out: {}", line);
                        sb.append(line);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }.start();
    }
}
