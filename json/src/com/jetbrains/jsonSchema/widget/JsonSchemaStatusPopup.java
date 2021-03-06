// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.jetbrains.jsonSchema.widget;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.containers.ContainerUtil;
import com.jetbrains.jsonSchema.extension.JsonSchemaInfo;
import com.jetbrains.jsonSchema.ide.JsonSchemaService;
import org.jetbrains.annotations.NotNull;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class JsonSchemaStatusPopup {
  static final JsonSchemaInfo ADD_SCHEMA = new JsonSchemaInfo("") {
    @Override
    public String getDescription() {
      return "Add new schema";
    }
  };

  static final JsonSchemaInfo EDIT_MAPPINGS = new JsonSchemaInfo("") {
    @Override
    public String getDescription() {
      return "Edit schema mappings";
    }
  };

  static final JsonSchemaInfo LOAD_REMOTE = new JsonSchemaInfo("") {
    @Override
    public String getDescription() {
      return "Load SchemaStore mappings";
    }
  };

  static ListPopup createPopup(@NotNull JsonSchemaService service,
                               @NotNull Project project,
                               @NotNull VirtualFile virtualFile,
                               boolean showOnlyEdit) {
    JsonSchemaInfoPopupStep step = createPopupStep(service, project, virtualFile, showOnlyEdit);
    return JBPopupFactory.getInstance().createListPopup(step);
  }

  @NotNull
  static JsonSchemaInfoPopupStep createPopupStep(@NotNull JsonSchemaService service,
                                                         @NotNull Project project,
                                                         @NotNull VirtualFile virtualFile, boolean showOnlyEdit) {
    List<JsonSchemaInfo> allSchemas;
    if (!showOnlyEdit) {
      List<JsonSchemaInfo> infos = service.getAllUserVisibleSchemas();
      Comparator<JsonSchemaInfo> comparator = Comparator.comparing(JsonSchemaInfo::getDescription, String::compareTo);
      Stream<JsonSchemaInfo> registered = infos.stream().filter(i -> i.getProvider() != null).sorted(comparator);
      List<JsonSchemaInfo> otherList = infos.stream().filter(i -> i.getProvider() == null).sorted(comparator).collect(Collectors.toList());
      if (otherList.size() == 0) {
        otherList = ContainerUtil.createMaybeSingletonList(LOAD_REMOTE);
      }
      allSchemas = Stream.concat(registered, otherList.stream()).collect(Collectors.toList());
      allSchemas.add(0, ADD_SCHEMA);
      allSchemas.add(1, EDIT_MAPPINGS);
    }
    else {
      allSchemas = ContainerUtil.createMaybeSingletonList(EDIT_MAPPINGS);
    }
    return new JsonSchemaInfoPopupStep(allSchemas, project, virtualFile, service);
  }
}
