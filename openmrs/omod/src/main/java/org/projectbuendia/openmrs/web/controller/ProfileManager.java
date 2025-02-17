// Copyright 2015 The Project Buendia Authors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not
// use this file except in compliance with the License.  You may obtain a copy
// of the License at: http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distrib-
// uted under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
// OR CONDITIONS OF ANY KIND, either express or implied.  See the License for
// specific language governing permissions and limitations under the License.

package org.projectbuendia.openmrs.web.controller;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.context.Context;
import org.openmrs.projectbuendia.webservices.rest.GlobalProperties;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.view.JstlView;
import org.springframework.web.servlet.view.RedirectView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** The controller for the profile management page. */
@Controller
public class ProfileManager {
    final File PROFILE_DIR = new File("/usr/share/buendia/profiles");
    final String VALIDATE_CMD = "buendia-profile-validate";
    final String APPLY_CMD = "buendia-profile-apply";
    static Log log = LogFactory.getLog(ProfileManager.class);

    @RequestMapping(value = "/module/projectbuendia/openmrs/profiles", method = RequestMethod.GET)
    public void get(HttpServletRequest request, ModelMap model) {
        model.addAttribute("user", Context.getAuthenticatedUser());
        model.addAttribute("profiles", PROFILE_DIR.listFiles());
        model.addAttribute("currentProfile",
                Context.getAdministrationService().getGlobalProperty(
                        GlobalProperties.CURRENT_PROFILE));
    }

    @RequestMapping(value = "/module/projectbuendia/openmrs/profiles", method = RequestMethod.POST)
    public View post(HttpServletRequest request, HttpServletResponse response, ModelMap model) {
        if (request instanceof MultipartHttpServletRequest) {
            addProfile((MultipartHttpServletRequest) request, model);
        } else {
            String filename = request.getParameter("profile");
            String op = request.getParameter("op");
            if (filename != null) {
                File file = new File(PROFILE_DIR, filename);
                if (file.isFile()) {
                    model.addAttribute("filename", filename);
                    if ("Apply".equals(op)) {
                        applyProfile(file, model);
                    } else if ("Download".equals(op)) {
                        downloadProfile(file, response);
                        return null;  // download the file, don't redirect
                    } else if ("Delete".equals(op)) {
                        deleteProfile(file, model);
                    }
                }
            }
        }
        return new RedirectView("profiles.form");  // reload this page with a GET request
    }

    /**
     * Executes a command with one argument, returning true if the command succeeds.
     * Gathers the output from stdout and stderr into the provided list of lines.
     */
    boolean execute(String command, File arg, List<String> lines) {
        ProcessBuilder pb = new ProcessBuilder(command, arg.getAbsolutePath());
        pb.redirectErrorStream(true);  // redirect stderr to stdout
        try {
            Process proc = pb.start();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) lines.add(line);
            proc.waitFor();
            return proc.exitValue() == 0;
        } catch (Exception e) {
            log.error("Exception while executing: " + command + " " + arg, e);
            return false;
        }
    }

    /** Sanitizes a string to produce a safe filename. */
    String sanitizeName(String filename) {
        String[] parts = filename.split("/");
        return parts[parts.length - 1].replaceAll("[^A-Za-z0-9._-]", " ").replaceAll(" +", " ");
    }

    /** Handles an uploaded profile. */
    void addProfile(MultipartHttpServletRequest request, ModelMap model) {
        List<String> lines = new ArrayList<>();
        MultipartFile mpf = request.getFile("file");
        if (mpf != null) {
            try {
                File tempFile = File.createTempFile("profile", null);
                mpf.transferTo(tempFile);
                if (execute(VALIDATE_CMD, tempFile, lines)) {
                    String filename = sanitizeName(mpf.getOriginalFilename());
                    File newFile = new File(PROFILE_DIR, filename);
                    model.addAttribute("filename", filename);
                    if (newFile.exists()) {
                        model.addAttribute("failure", "add");
                        model.addAttribute("output", "A profile named " + filename + " already exists.");
                    } else {
                        FileUtils.moveFile(tempFile, newFile);
                        model.addAttribute("success", "add");
                    }
                } else {
                    model.addAttribute("failure", "add");
                    model.addAttribute("filename", mpf.getOriginalFilename());
                    model.addAttribute("output", StringUtils.join(lines, "\n"));
                }
            } catch (Exception e) {
                log.error("Problem saving uploaded profile", e);
            }
        }
    }

    /** Downloads a profile. */
    public void downloadProfile(File file, HttpServletResponse response) {
        response.setContentType("application/octet-stream");
        response.setHeader(
                "Content-Disposition", "attachment; filename=\"" + file.getName() + "\"");
        try {
            response.getOutputStream().write(
                    Files.readAllBytes(Paths.get(file.getAbsolutePath())));
        } catch (IOException e) {
            log.error("Error downloading profile: " + file.getName(), e);
        }
    }

    /** Applies a profile to the OpenMRS database. */
    void applyProfile(File file, ModelMap model) {
        List<String> lines = new ArrayList<>();
        if (execute(APPLY_CMD, file, lines)) {
            setCurrentProfile(file.getName());
            model.addAttribute("success", "apply");
        } else {
            model.addAttribute("failure", "apply");
            model.addAttribute("output", StringUtils.join(lines, "\n"));
        }
    }

    /** Deletes a profile. */
    void deleteProfile(File file, ModelMap model) {
        if (file.getName().equals(getCurrentProfile())) {
            model.addAttribute("failure", "delete");
            model.addAttribute("output", "Cannot delete the currently active profile.");
        } else if (file.delete()) {
            model.addAttribute("success", "delete");
        } else {
            log.error("Error deleting profile: " + file.getName());
            model.addAttribute("failure", "delete");
        }
    }

    /** Sets the global property for the name of the current profile. */
    void setCurrentProfile(String name) {
        Context.getAdministrationService().setGlobalProperty(
                GlobalProperties.CURRENT_PROFILE, name);
    }

    /** Gets the global property for the name of the current profile. */
    String getCurrentProfile() {
        return Context.getAdministrationService().getGlobalProperty(
                GlobalProperties.CURRENT_PROFILE);
    }
}
