package com.ray.qjc.common.utils;

import com.aliyuncs.exceptions.ClientException;
import com.ray.qjc.common.api.APIResponse;
import com.ray.qjc.common.enums.EventEnum;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.poi.util.IOUtils;
import org.springframework.util.CollectionUtils;
import org.springframework.web.multipart.MultipartFile;
import sun.net.ftp.FtpClient;

import java.io.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @ClassName FtpUtil
 * @Description ftp工具类
 * @Date 2020/3/16 16:09
 * @Author luorenjie
 * @Version 1.0
 * @Since JDK 1.8
 */
@Data
@Slf4j
public class FtpUtil {
    //本地字符编码
    private static String LOCAL_CHARSET = "GBK";
    // FTP协议里面，规定文件名编码为iso-8859-1
    private static String SERVER_CHARSET = "ISO-8859-1";

    public static final String ZIP = "zip";
    public static final String RAR = "rar";
    public static final String JPG = "jpg";
    public static final String PNG = "png";

    /**
     * 连接到ftp服务器
     *
     * @param host
     * @param port
     * @param user
     * @param passWord
     * @return
     */
    public static FTPClient connectToFtp(String host, int port, String user, String passWord) {
        int reply;
        FTPClient ftpClient;
        try {
            ftpClient = new FTPClient();
            ftpClient.connect(host, port);
            ftpClient.login(user, passWord);
            reply = ftpClient.getReplyCode();
            if (!FTPReply.isPositiveCompletion(reply)) {
                ftpClient.disconnect();
                log.info("连接不上ftp服务器，错误码：{}", reply);
                return null;
            }
        } catch (Exception e) {
            log.error("登录ftp服务器【" + host + "】失败", e);
            return null;
        }
        return ftpClient;
    }

    /**
     * 开启服务器对UTF-8的支持
     * 判断并设置编码为utf-8或者本地编码
     *
     * @param ftpClient
     * @throws IOException
     */
    private static void setFtpEncoding(FTPClient ftpClient) throws IOException {
        if (FTPReply.isPositiveCompletion(ftpClient.sendCommand("OPTS UTF8", "ON"))) {
            LOCAL_CHARSET = "UTF-8";
        }
        ftpClient.setControlEncoding(LOCAL_CHARSET);
    }

    /**
     * 关闭当前ftp连接
     *
     * @param ftpClient
     */
    public static void closeConnect(FTPClient ftpClient) {
        try {
            if (ftpClient != null) {
                ftpClient.logout();
                ftpClient.disconnect();
            }
        } catch (Exception e) {
            log.error("ftp连接关闭失败！", e);
        }
    }

    /**
     * 功能：上传文件到ftp文件服务器
     *
     * @param inputStream:上传文件流
     * @param ftpClient:ftp连接客户端
     * @param fileName:保存文件名称
     * @return
     */
    public static boolean uploadFile(InputStream inputStream, FTPClient ftpClient, String fileName, String remoteFilePath) {
        boolean returnValue = false;
        // 上传文件
        try {
            // 设置传输二进制文件
            ftpClient.setFileType(FTP.BINARY_FILE_TYPE);
            //被动模式，开启端口传输数据
            ftpClient.enterLocalPassiveMode();

            createDirecroty(remoteFilePath, ftpClient);
            //判断并设置ftp编码为utf-8或者本地编码
            setFtpEncoding(ftpClient);
            // 上传文件到ftp
            returnValue = ftpClient.storeFile(new String(fileName.getBytes(LOCAL_CHARSET), SERVER_CHARSET), inputStream);

        } catch (Exception e) {
            returnValue = false;
            log.error("上传文件到服务器失败", e);
            return returnValue;
        } finally {
            try {
                if (inputStream != null) {
                    inputStream.close();
                }
            } catch (Exception e) {
                log.error("ftp关闭输入流时失败！", e);
            }
        }
        return returnValue;
    }

    /**
     * 上传文件
     *
     * @param ftpClient
     * @param multipartFile
     * @param remote
     * @return
     * @throws Exception
     */
    public static APIResponse upload(FTPClient ftpClient, MultipartFile multipartFile, String remote) throws Exception {
        // 设置PassiveMode传输
        ftpClient.enterLocalPassiveMode();
        // 设置以二进制流的方式传输
        ftpClient.setFileType(FTP.BINARY_FILE_TYPE);
        //判断并设置ftp编码为utf-8或者本地编码
        setFtpEncoding(ftpClient);
        boolean result;
        File file = FileUtil.multipartFileToFile(multipartFile);

        //远程文件名
        String remoteFileName = remote.substring(remote.lastIndexOf("/") + 1);
        // 创建服务器远程目录
        createDirecroty(remote, ftpClient);

        // 检查远程是否存在文件
        FTPFile[] files = ftpClient.listFiles(new String(remoteFileName.getBytes(LOCAL_CHARSET), SERVER_CHARSET));
        if (files.length == 1) {
            long remoteSize = files[0].getSize();
            long localSize = file.length();
            if (remoteSize == localSize) {
                return APIResponse.returnFail(EventEnum.FILE_EXISTS);
            } else if (remoteSize > localSize) {
                return APIResponse.returnFail(EventEnum.REMOTE_ISBIGGERTHAN_LOCAL);
            }

            // 尝试移动文件内读取指针,实现断点续传
            result = uploadFile(remoteFileName, file, ftpClient, remoteSize);

            // 如果断点续传没有成功，则删除服务器上文件，重新上传
            if (!result) {
                if (!ftpClient.deleteFile(remoteFileName)) {
                    return APIResponse.returnFail(EventEnum.DELETE_REMOTE_FAILD);
                }
                uploadFile(remoteFileName, file, ftpClient, 0);
            }
        } else {
            uploadFile(remoteFileName, file, ftpClient, 0);
        }
        return APIResponse.returnSuccess("上传成功");
    }

    /**
     * 断点续传
     *
     * @param remoteFile
     * @param localFile
     * @param ftpClient
     * @param remoteSize
     * @return
     * @throws IOException
     */
    public static boolean uploadFile(String remoteFile, File localFile, FTPClient ftpClient, long remoteSize) throws IOException {
        boolean status;
        // 显示进度的上传
        long step = localFile.length() / 100;
        long process = 0;
        long localreadbytes = 0L;
        RandomAccessFile raf = new RandomAccessFile(localFile, "r");
        OutputStream out = ftpClient.appendFileStream(new String(remoteFile.getBytes(LOCAL_CHARSET), SERVER_CHARSET));
        // 断点续传
        if (remoteSize > 0) {
            ftpClient.setRestartOffset(remoteSize);
            process = remoteSize / step;
            raf.seek(remoteSize);
            localreadbytes = remoteSize;
        }
        byte[] bytes = new byte[1024];
        int c;
        while ((c = raf.read(bytes)) != -1) {
            out.write(bytes, 0, c);
            localreadbytes += c;
            if (localreadbytes / step != process) {
                process = localreadbytes / step;
                log.info("上传进度:" + process);
            }
        }
        out.flush();
        raf.close();
        out.close();
        boolean result = ftpClient.completePendingCommand();
        if (remoteSize > 0) {
            status = result ? true : false;
        } else {
            status = result ? true : false;
        }
        //释放ftp客户端
        closeConnect(ftpClient);
        return status;
    }

    /**
     * 从服务器下载文件
     *
     * @param ftpClient
     * @param remoteFilePath
     * @return
     */
    public static InputStream downloadFile(FTPClient ftpClient, String remoteFilePath) {
        InputStream inputStream = null;
        String dir = remoteFilePath.substring(0, remoteFilePath.lastIndexOf("/"));
        String file = remoteFilePath.substring(remoteFilePath.lastIndexOf("/") + 1);
        try {
            ftpClient.enterLocalPassiveMode();
            // 判断并设置ftp编码为utf-8或者本地编码
            setFtpEncoding(ftpClient);
            //cd到根目录
            ftpClient.changeWorkingDirectory("/");
            ftpClient.changeWorkingDirectory(new String(dir.getBytes(LOCAL_CHARSET), SERVER_CHARSET));

            // 检验文件是否存在
            inputStream = ftpClient.retrieveFileStream(new String(file.getBytes(LOCAL_CHARSET), SERVER_CHARSET));
        } catch (IOException e) {
            e.printStackTrace();
        }
        return inputStream;
    }

    /**
     * 判断文件是否存在
     *
     * @param filePath
     * @param ftpClient
     * @return
     */
    public static boolean isExsits(String filePath, FTPClient ftpClient) {
        try {
            String dir = filePath.substring(0, filePath.lastIndexOf("/"));
            String file = filePath.substring(filePath.lastIndexOf("/") + 1);

            ftpClient.enterLocalPassiveMode();
            // 设置文件类型为二进制，与ASCII有区别
            ftpClient.setFileType(FTP.BINARY_FILE_TYPE);

            // 判断并设置ftp编码为utf-8或者本地编码
            setFtpEncoding(ftpClient);
            //cd到根目录
            ftpClient.changeWorkingDirectory("/");
            ftpClient.changeWorkingDirectory(new String(dir.getBytes(LOCAL_CHARSET), SERVER_CHARSET));
            System.out.println(new String(ftpClient.printWorkingDirectory().getBytes(LOCAL_CHARSET), SERVER_CHARSET));
            FTPFile[] ftpFileArr = ftpClient.listFiles(new String(file.getBytes(LOCAL_CHARSET), SERVER_CHARSET));
            System.out.println(ftpFileArr);
            for (FTPFile ftpFile : ftpFileArr) {
                if (ftpFileArr.length > 0) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.error("创建文件失败", e);
            return false;
        }
        return false;
    }


    /**
     * 递归创建远程服务器目录
     *
     * @param remoteFilePath 远程服务器文件绝对路径
     * @param ftpClient FTPClient对象
     * @return 目录创建是否成功
     * @throws IOException
     */
    private static boolean createDirecroty(String remoteFilePath, FTPClient ftpClient) throws IOException {
        String directory = remoteFilePath.substring(0, remoteFilePath.lastIndexOf("/") + 1);
        String dirName = new String(directory.getBytes(LOCAL_CHARSET), SERVER_CHARSET);
        //cd到根目录
        boolean a = ftpClient.changeWorkingDirectory("/");
        String[] dirs = dirName.split("/");
        for (String dir : dirs) {
            if (null == dir || "".equals(dir)) {
                continue;//跳出本地循环，进入下一次循环
            }
            if (!ftpClient.changeWorkingDirectory(dir)) {
                if (ftpClient.makeDirectory(dir)) {
                    ftpClient.changeWorkingDirectory(dir);
                }
            }
        }
        return true;
    }

    /**
     * 移动文件
     *
     * @param ftpClient
     * @param fileName
     * @param targetPicPath
     * @param targetFilePath
     * @return
     */
    public static boolean removeFiles(FTPClient ftpClient, String fileName, String targetPicPath, String targetFilePath, String tempFilePath) {
        try {
            // 设置PassiveMode传输
            ftpClient.enterLocalPassiveMode();
            setFtpEncoding(ftpClient);

            //临时文件目录
            String directory = tempFilePath.substring(0, tempFilePath.lastIndexOf("/") + 1);

            // 创建服务器远程目录
            if (targetPicPath != null) {
                createDirecroty(targetPicPath, ftpClient);
            }
            createDirecroty(targetFilePath, ftpClient);

            //cd到根目录
            ftpClient.changeWorkingDirectory("/");
            ftpClient.changeWorkingDirectory(new String(directory.getBytes(LOCAL_CHARSET), SERVER_CHARSET));

            System.out.println(ftpClient.printWorkingDirectory());

            FTPFile[] files = ftpClient.listFiles();
            List<String> fileNameList = new ArrayList<>();
            List<String> md5List = new ArrayList<>();
            for (FTPFile file : files) {
                if (file.getName().equals(fileName + "." + ZIP)
                        || file.getName().equals(fileName + "." + RAR)
                        || file.getName().equals(fileName + "." + JPG)) {
                    fileNameList.add(file.getName());
                }
                if (file.getName().contains("_")) {
                    md5List.add(file.getName());
                }
            }

            if (!CollectionUtils.isEmpty(fileNameList)) {
                Collections.sort(fileNameList);

                if (targetFilePath == null) {
                    boolean s = copyFile(ftpClient, fileNameList.get(0), targetPicPath);
                    if (!s){
                        return false;
                    }
                } else {
                    for (String name : fileNameList) {
                        if (name.equals(fileName + "." + ZIP)
                                || name.equals(fileName + "." + RAR)) {
                            //cd到根目录
                            ftpClient.changeWorkingDirectory("/");
                            ftpClient.changeWorkingDirectory(new String(directory.getBytes(LOCAL_CHARSET), SERVER_CHARSET));
                            boolean s = copyFile(ftpClient, name, targetFilePath);
                            if (!s) {
                                return false;
                            }
                        }

                        if (name.equals(fileName + "." + JPG)) {
                            //cd到根目录
                            ftpClient.changeWorkingDirectory("/");
                            ftpClient.changeWorkingDirectory(new String(directory.getBytes(LOCAL_CHARSET), SERVER_CHARSET));
                            boolean s = copyFile(ftpClient, name, targetPicPath);
                            if (!s) {
                                return false;
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.error("移动文件出现异常：", e);
            return false;
        }
        return true;
    }

    /**
     * 复制文件
     * @param ftpClient
     * @param fileName
     * @param targetPath
     * @return
     * @throws IOException
     */
    private static boolean copyFile(FTPClient ftpClient, String fileName, String targetPath) throws IOException {
        InputStream input = ftpClient.retrieveFileStream(new String((fileName).getBytes(LOCAL_CHARSET), SERVER_CHARSET));
        byte[] bytes = IOUtils.toByteArray(input);
        //ftp传输结束
        ftpClient.completePendingCommand();
        System.out.println(ftpClient.printWorkingDirectory());
        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        ftpClient.changeWorkingDirectory(targetPath);
        ftpClient.enterLocalActiveMode();
        setFtpEncoding(ftpClient);
        // 设置以二进制流的方式传输
        ftpClient.setFileType(FTPClient.BINARY_FILE_TYPE);
        boolean s = ftpClient.storeFile(new String(fileName.getBytes(LOCAL_CHARSET), SERVER_CHARSET), in);
        return s;
    }

    /**
     * 对ftp的Dir进行格式化处理
     *
     * @param fdir
     * @return
     */
    private String formatDir(String fdir) {
        fdir = fdir.replace("/", "\\").replace("\\\\", "\\");
        if (fdir.startsWith("\\")) {
            fdir = fdir.substring(fdir.indexOf("\\") + 1);
        }
        if (fdir.endsWith("\\")) {
            fdir = fdir.substring(0, fdir.lastIndexOf("\\"));
        }
        return fdir;
    }

    /**
     * 文件重命名
     * @param ftpClient
     * @param oldName
     * @param filePath
     * @return
     */
    public static boolean renameFile(FTPClient ftpClient, String oldName, String filePath) {
        try {
            String dir = filePath.substring(0, filePath.lastIndexOf("/"));
            String newName = filePath.substring(filePath.lastIndexOf("/") + 1);
            //cd到根目录
            ftpClient.changeWorkingDirectory("/");
            ftpClient.changeWorkingDirectory(dir);
            ftpClient.enterLocalActiveMode();
            setFtpEncoding(ftpClient);
            boolean s = ftpClient.rename(new String(oldName.getBytes(LOCAL_CHARSET), SERVER_CHARSET), newName);
            if (!s){
                return false;
            }
        } catch (IOException e) {
            log.error("文件重命名异常", e);
            return false;
        }
        return true;
    }

    /**
     * 列出指定目录下Ftp服务器上的所有文件名称
     *
     * @return
     */
    public static List<FTPFile> listRemoteAllFiles(FTPClient ftpClient, String remotePath) throws ClientException {
        //目标路径
        String dir = remotePath.substring(0, remotePath.lastIndexOf("/"));
        ftpClient.enterLocalPassiveMode();
        List<FTPFile> nameList = new ArrayList<>();
        try {
            // 设置文件类型为二进制，与ASCII有区别
            ftpClient.setFileType(FTP.BINARY_FILE_TYPE);
            setFtpEncoding(ftpClient);
            //cd到根目录
            ftpClient.changeWorkingDirectory("/");
            ftpClient.changeWorkingDirectory(new String(dir.getBytes(LOCAL_CHARSET), SERVER_CHARSET));
            FTPFile[] files = ftpClient.listFiles();
            String[] names = ftpClient.listNames();
            System.out.println(names);
            for (int i = 0; i < files.length; i++) {
                nameList.add(files[i]);
            }
            return nameList;
        } catch (IOException e) {
            e.printStackTrace();
            throw new ClientException("ftp操作异常", e.getMessage());
        }
    }

    public static boolean mergeFiles(FTPClient ftpClient, String remotePath, List<String> nameList, long totalSize) {
        //服务器文件目录
        String dir = remotePath.substring(0, remotePath.lastIndexOf("/"));
        //合并文件名称
        String file = remotePath.substring(remotePath.lastIndexOf("/") + 1);
        //合并文件后缀
        String fileSuffix = file.substring(file.lastIndexOf(".") + 1);
        try {
            ftpClient.enterLocalActiveMode();
            // 设置文件类型为二进制，与ASCII有区别
            ftpClient.setFileType(FTP.BINARY_FILE_TYPE);
//            // 判断并设置ftp编码为utf-8或者本地编码
//            setFtpEncoding(ftpClient);

            //cd到根目录
            ftpClient.changeWorkingDirectory("/");
            ftpClient.changeWorkingDirectory(new String(dir.getBytes(LOCAL_CHARSET), SERVER_CHARSET));

            List<byte[]> listByte = new ArrayList<>();
            //将分片文件流读取到集合
            InputStream in;
            long size = 0;
            for (String name : nameList) {
                log.info("分片文件>>>>>>>>" + name);
                //读取小文件的输入流
                in = ftpClient.retrieveFileStream(name);
                byte[] bytes = IOUtils.toByteArray(in);
                size = size + bytes.length;
                //关闭输入流
                in.close();
                //ftp传输结束
                ftpClient.completePendingCommand();
                listByte.add(bytes);
            }
            if (totalSize != size) {
                log.error("服务器文件大小【{}】和文件总大小【{}】不一致", listByte.size(), totalSize);
                return false;
            }

            //开始合并文件
            for (byte[] bytes : listByte) {
                in = new ByteArrayInputStream(bytes);
                boolean flag;
                if (fileSuffix.toLowerCase().equals(PNG)) {
                    String picName = file.substring(0, file.lastIndexOf(".")) + "." + JPG;
                    flag = ftpClient.appendFile(new String(picName.getBytes(LOCAL_CHARSET), SERVER_CHARSET), in);
                } else {
                    flag = ftpClient.appendFile(new String(file.getBytes(LOCAL_CHARSET), SERVER_CHARSET), in);
                }
                if (!flag) {
                    return false;
                }
                log.info("=============文件合并成功=============");
                in.close();
                return true;
            }
        } catch (IOException e) {
            log.error("合并文件发生异常:{}", e.getMessage());
            return false;
        }
        return false;
    }

    /**
     * 删除文件
     *
     * @param ftpClient
     * @param filePath
     * @return
     */
    public static boolean deleteRemoteFile(FTPClient ftpClient, String filePath) {
        try {
            String dir = filePath.substring(0, filePath.lastIndexOf("/"));
            String file = filePath.substring(filePath.lastIndexOf("/") + 1);

            //cd 到根目录
            ftpClient.changeWorkingDirectory("/");
            ftpClient.changeWorkingDirectory(new String(dir.getBytes(LOCAL_CHARSET), SERVER_CHARSET));
            boolean s = ftpClient.deleteFile(new String(file.getBytes(LOCAL_CHARSET), SERVER_CHARSET));
            if (!s){
                return false;
            }
        } catch (IOException e) {
            log.error("文件删除失败", e);
            return false;
        }
        return true;
    }
}
