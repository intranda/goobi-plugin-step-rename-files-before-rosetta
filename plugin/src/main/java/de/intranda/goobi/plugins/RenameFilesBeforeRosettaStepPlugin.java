package de.intranda.goobi.plugins;

import java.io.IOException;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;

/**
 * This file is part of a plugin for Goobi - a Workflow tool for the support of mass digitization.
 *
 * Visit the websites for more information.
 *          - https://goobi.io
 *          - https://www.intranda.com
 *          - https://github.com/intranda/goobi
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program; if not, write to the Free Software Foundation, Inc., 59
 * Temple Place, Suite 330, Boston, MA 02111-1307 USA
 *
 */

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.lang.StringUtils;
import org.goobi.beans.Process;
import org.goobi.beans.Step;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginReturnValue;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IStepPluginVersion2;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.StorageProviderInterface;
import de.sub.goobi.helper.VariableReplacer;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.ContentFile;
import ugh.dl.DigitalDocument;
import ugh.dl.FileSet;
import ugh.dl.Fileformat;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;
import ugh.exceptions.WriteException;

@PluginImplementation
@Log4j2
public class RenameFilesBeforeRosettaStepPlugin implements IStepPluginVersion2 {
    private static final String DEFAULT_FORMAT = "0000";
    private static final String TEMP_FOLDER = "temp";
    
    @Getter
    private String title = "intranda_step_rename_files_before_rosetta";
    @Getter
    private Step step;

    private String returnPath;

    private Process process;
    // common prefix of the new file name
    private String newFileNamePrefix;
    // path as string of the media folder
    private String derivateFolder;
    // format that will be used in the creation of new names
    private NumberFormat format;
    private VariableReplacer variableReplacer;

    private transient StorageProviderInterface storageProvider = StorageProvider.getInstance();

    @Override
    public void initialize(Step step, String returnPath) {
        this.returnPath = returnPath;
        this.step = step;
        this.variableReplacer = createVariableReplacer(step.getProzess());
                
        // read parameters from correct block in configuration file
        SubnodeConfiguration config = ConfigPlugins.getProjectAndStepConfig(title, step);

        String formatFlag = config.getString("format");
        if (StringUtils.isBlank(formatFlag)) {
            formatFlag = DEFAULT_FORMAT;
        }
        format = new DecimalFormat(formatFlag);
        
        String configuredMainImagesPath = config.getString("mainImageFolder", "{tifpath}");
        if(StringUtils.isNotBlank(configuredMainImagesPath) && variableReplacer != null) {
            derivateFolder = this.variableReplacer.replace(configuredMainImagesPath);
        } else {
            try {
                derivateFolder = process.getImagesTifDirectory(false);
            } catch (IOException | SwapException e) {
                log.error("Error getting image folder for process {}: {}", process.getId(), e.toString());
                derivateFolder = null;
            }
        }
        

        process = this.step.getProzess();
        String processTitle = process.getTitel();
        newFileNamePrefix = processTitle.substring(processTitle.indexOf("_") + 1);
        
        log.info("rename_files_before_rosetta step plugin initialized");
    }
    
    private VariableReplacer createVariableReplacer(Process process) {
        try {
            Fileformat fileformat = process.readMetadataFile();
            return new VariableReplacer(fileformat != null ? fileformat.getDigitalDocument() : null,
                    process.getRegelsatz().getPreferences(), process, step);
        } catch (ReadException | IOException | SwapException | PreferencesException e1) {
            log.error("Errors happened while trying to initialize the Fileformat and VariableReplacer.");
            log.error(e1);
            return null;
        }
    }

    @Override
    public PluginReturnValue run() {
        // 1. create a Map from old names to new names
        Map<String, String> namesMap = createNamesMap();

        // 2. rename files in each folder with help of this Map
        boolean successful = !namesMap.isEmpty() && renameFiles(namesMap);

        // 3. update the Mets file
        successful = successful && updateMetsFile(namesMap);

        log.info("rename_files_before_rosetta step plugin executed");
        return successful ? PluginReturnValue.FINISH : PluginReturnValue.ERROR;
    }

    /**
     * create a Map from old names to new names
     * 
     * @return a Map from old names to new names
     */
    private Map<String, String> createNamesMap() {
        Map<String, String> namesMap = new HashMap<>();
        boolean validDerivateFolder = checkDerivateFolder();
        if (!validDerivateFolder) {
            return namesMap;
        }

        // derivate folder is valid
        List<Path> files = storageProvider.listFiles(derivateFolder);
        boolean validFormat = checkFormat(files.size());
        log.debug("format is {}valid", validFormat ? "" : "in");
        if (!validFormat) {
            log.error("The configured format does not have enough digits. Please adjust it.");
            return namesMap;
        }

        // format is also valid
        for (int i = 0; i < files.size(); ++i) {
            Path file = files.get(i);
            String fileName = file.getFileName().toString();
            String oldName = fileName.substring(0, fileName.lastIndexOf("."));
            // get new name based on the order of this file
            String newName = createNewName(i + 1);
            namesMap.put(oldName, newName);
        }

        return namesMap;
    }

    /**
     * checks if the derivate folder is valid, that is if the derivate folder is different from the master folder AND it actually exists
     * 
     * @return true if the derivate folder is valid, false otherwise OR any Exceptions occurs
     */
    private boolean checkDerivateFolder() {
        try {
            String masterFolder = process.getImagesOrigDirectory(false);

            return !masterFolder.equals(derivateFolder) && storageProvider.isFileExists(Path.of(derivateFolder));

        } catch (IOException | SwapException | DAOException e) {
            log.error("Errors Happened during the validity check of the derivate folder");
            e.printStackTrace();
            return false;
        }
    }

    /**
     * checks if the configured format has enough digits for keeping at most n files' names of equal lengths
     * 
     * @param n the total number of files that should be renamed
     * @return true if the configured format has enough digits, false otherwise
     */
    private boolean checkFormat(int n) {
        String format_0 = format.format(0); // NOSONAR
        String format_n = format.format(n); // NOSONAR

        return format_0.length() == format_n.length();
    }

    /**
     * create a new name given the order of the file
     * 
     * @param order the order of the file among all files
     * @return {newFileNamePrefix}_{the formated order}
     */
    private String createNewName(int order) {
        String formatedNumber = format.format(order);
        String newName = newFileNamePrefix + "_" + formatedNumber;

        return newName;
    }

    /**
     * rename all files that are relevant
     * 
     * @param namesMap Map from old names to new names
     * @return true if all relevant files are successfully renamed, false otherwise
     */
    private boolean renameFiles(Map<String, String> namesMap) {
        List<String> folders = getFolderList();
        boolean success = !folders.isEmpty();

        // rename files in each folder
        for (String folder : folders) {
            success = success && renameFilesInFolder(folder, namesMap);
        }

        return success;
    }

    /**
     * get a list of folders whose files would be renamed
     * 
     * @return the list of folders whose files would be renamed, or an empty list if any error should occur
     */
    private List<String> getFolderList() {
        List<String> folders = new ArrayList<>();

        try {
            String altoFolder = process.getOcrAltoDirectory();
            String pdfFolder = process.getOcrPdfDirectory();
            String txtFolder = process.getOcrTxtDirectory();
            String xmlFolder = process.getOcrXmlDirectory();

            // existence of derivateFolder was already checked by the method checkDerivateFolder
            log.debug("add derivateFolder: " + derivateFolder);
            folders.add(derivateFolder);

            if (storageProvider.isFileExists(Path.of(altoFolder))) {
                log.debug("add altoFolder: " + altoFolder);
                folders.add(altoFolder);
            }

            if (storageProvider.isFileExists(Path.of(pdfFolder))) {
                log.debug("add pdfFolder: " + pdfFolder);
                folders.add(pdfFolder);
            }

            if (storageProvider.isFileExists(Path.of(txtFolder))) {
                log.debug("add txtFolder: " + txtFolder);
                folders.add(txtFolder);
            }

            if (storageProvider.isFileExists(Path.of(xmlFolder))) {
                log.debug("add xmlFolder: " + xmlFolder);
                folders.add(xmlFolder);
            }
        } catch (IOException | SwapException e) {
            log.error("Failed to get the folder list.");
            e.printStackTrace();
        }

        return folders;
    }

    /**
     * rename all files in the given folder
     * 
     * @param folder path as string of the folder
     * @param namesMap Map from old names to new names
     * @return true if all files in the given folder are successfully renamed, false otherwise
     */
    private boolean renameFilesInFolder(String folder, Map<String, String> namesMap) {
        List<Path> files = storageProvider.listFiles(folder);

        for (Path file : files) {
            // get new filename 
            String oldFileName = file.getFileName().toString();
            String newFileName = getNewFileName(oldFileName, namesMap);

            try {
                tryRenameFile(file, newFileName);
            } catch (IOException e) {
                log.error("IOException happened trying to move {}", file);
                return false;
            }
        }

        // move all the files from the temp folder back again
        try {
            moveFilesFromTempBack(folder);
        } catch (IOException e) {
            log.error("IOException caught while trying to get files from the temp folder back.");
            return false;
        }

        return true;
    }

    /**
     * get the new file name given the old one
     * 
     * @param oldFileName the old file name, including the file suffix
     * @param namesMap Map from old names to new names
     * @return the new file name including the file suffix
     */
    private String getNewFileName(String oldFileName, Map<String, String> namesMap) {
        int suffixIndex = oldFileName.lastIndexOf(".");
        String suffix = oldFileName.substring(suffixIndex);
        String oldName = oldFileName.substring(0, suffixIndex);
        return namesMap.get(oldName).concat(suffix);
    }

    /**
     * try to rename a file
     * 
     * @param filePath the Path of the file which is to be renamed
     * @param newFileName the new name of the file
     * @throws IOException
     */
    private void tryRenameFile(Path filePath, String newFileName) throws IOException {
        Path targetPath = filePath.getParent().resolve(newFileName);
        if (storageProvider.isFileExists(targetPath)) {
            log.debug("targetPath is occupied: " + targetPath.toString());
            log.debug("Moving the file " + newFileName + " to temp folder for the moment instead.");
            // move files to a temp folder
            moveFileToTempFolder(filePath, newFileName);
        } else {
            storageProvider.move(filePath, targetPath);
        }
    }

    /**
     * move files whose new names have conflicts with other files to a temp folder for the moment
     * 
     * @param filePath the Path of the file which is to be moved
     * @param newFileName the new name of this file
     * @throws IOException
     */
    private void moveFileToTempFolder(Path filePath, String newFileName) throws IOException {
        Path tempFolderPath = Path.of(filePath.getParent().toString(), TEMP_FOLDER);
        if (!storageProvider.isFileExists(tempFolderPath)) {
            storageProvider.createDirectories(tempFolderPath);
        }
        storageProvider.move(filePath, tempFolderPath.resolve(newFileName));
    }

    /**
     * move files back from the temp folder
     * 
     * @param folder the path as string of the folder whose files have just been renamed
     * @throws IOException
     */
    private void moveFilesFromTempBack(String folder) throws IOException {
        Path tempFolderPath = Path.of(folder, TEMP_FOLDER);
        if (storageProvider.isFileExists(tempFolderPath)) {
            log.debug("Moving files back from the temp folder: " + tempFolderPath.toString());
            List<Path> files = storageProvider.listFiles(tempFolderPath.toString());
            for (Path file : files) {
                storageProvider.move(file, Path.of(folder, file.getFileName().toString()));
            }
            storageProvider.deleteDir(tempFolderPath);
            log.debug("Temp folder deleted: " + tempFolderPath.toString());
        }
    }

    /**
     * update information of ContentFiles' locations in the METS file
     * 
     * @param namesMap Map from old names to new names
     * @return true if the METS file is updated successfully, false if any error should occur
     */
    private boolean updateMetsFile(Map<String, String> namesMap) {
        try {
            Fileformat fileformat = process.readMetadataFile();
            DigitalDocument dd = fileformat.getDigitalDocument();
            FileSet fileSet = dd.getFileSet();
            List<ContentFile> filesList = fileSet.getAllFiles();
            for (ContentFile file : filesList) {
                String oldLocation = file.getLocation();
                int fileNameStartIndex = oldLocation.lastIndexOf("/") + 1;
                String locationPrefix = oldLocation.substring(0, fileNameStartIndex);

                String oldFileName = oldLocation.substring(fileNameStartIndex);
                String newFileName = getNewFileName(oldFileName, namesMap);
                String newLocation = locationPrefix.concat(newFileName);
                file.setLocation(newLocation);
            }

            process.writeMetadataFile(fileformat);
            return true;

        } catch (ReadException | IOException | SwapException | PreferencesException | WriteException e) {
            log.error("Failed to update the Mets file.");
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public PluginGuiType getPluginGuiType() {
        return PluginGuiType.NONE;
    }

    @Override
    public String getPagePath() {
        return null;
    }

    @Override
    public PluginType getType() {
        return PluginType.Step;
    }

    @Override
    public String cancel() {
        return "/uii" + returnPath;
    }

    @Override
    public String finish() {
        return "/uii" + returnPath;
    }
    
    @Override
    public int getInterfaceVersion() {
        return 0;
    }

    @Override
    public HashMap<String, StepReturnValue> validate() {
        return null; // NOSONAR
    }
    
    @Override
    public boolean execute() {
        PluginReturnValue ret = run();
        return ret != PluginReturnValue.ERROR;
    }

}
