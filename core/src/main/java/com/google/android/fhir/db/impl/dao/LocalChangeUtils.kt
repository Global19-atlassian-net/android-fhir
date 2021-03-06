/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.fhir.db.impl.dao

import android.util.Log
import ca.uhn.fhir.parser.IParser
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.fge.jsonpatch.JsonPatch
import com.github.fge.jsonpatch.diff.JsonDiff
import com.google.android.fhir.db.impl.entities.LocalChange
import java.lang.IllegalArgumentException
import org.hl7.fhir.r4.model.Resource

object LocalChangeUtils {

    /**
     * Squash the changes by merging them two at a time.
     */
    @JvmStatic
    fun squash(localChanges: List<LocalChange>): LocalChange =
            localChanges.reduce { first, second -> mergeLocalChanges(first, second) }

    @JvmStatic
    fun mergeLocalChanges(first: LocalChange, second: LocalChange): LocalChange {
        val type: LocalChange.Type
        val payload: String
        when (second.type) {
            LocalChange.Type.UPDATE -> when {
                first.type.equals(LocalChange.Type.UPDATE) -> {
                    type = LocalChange.Type.UPDATE
                    payload = mergePatches(first.payload, second.payload)
                }
                first.type.equals(LocalChange.Type.INSERT) -> {
                    type = LocalChange.Type.INSERT
                    payload = applyPatch(first.payload, second.payload)
                }
                else -> {
                    throw IllegalArgumentException(
                        "Cannot merge local changes with type ${first.type} and ${second.type}."
                    )
                }
            }
            LocalChange.Type.DELETE -> {
                type = LocalChange.Type.DELETE
                payload = ""
            }
            LocalChange.Type.INSERT -> {
                type = LocalChange.Type.INSERT
                payload = second.payload
            }
        }
        return LocalChange(
            id = 0,
            resourceId = second.resourceId,
            resourceType = second.resourceType,
            type = type,
            payload = payload
        )
    }

    /**
     * Update a JSON object with a JSON patch (RFC 6902).
     */
    @JvmStatic
    private fun applyPatch(resourceString: String, patchString: String): String {
        val objectMapper = ObjectMapper()
        val resourceJson = objectMapper.readValue(resourceString, JsonNode::class.java)
        val patchJson = objectMapper.readValue(patchString, JsonPatch::class.java)
        return patchJson.apply(resourceJson).toString()
    }

    /**
     * Merge two JSON patch strings by concatenating their elements into a new JSON array.
     */
    @JvmStatic
    private fun mergePatches(firstPatch: String, secondPatch: String): String {
        val objectMapper = ObjectMapper()
        // TODO: validate patches are RFC 6902 compliant JSON patches
        val first = objectMapper.readValue(firstPatch, JsonPatch::class.java)
        val updater = objectMapper.readerForUpdating(first)
        val merged = updater.readValue(secondPatch, JsonPatch::class.java)
        return merged.toString()
    }

    /**
     * Calculates the JSON patch between two [Resource]s.
     */
    @JvmStatic
    internal fun diff(parser: IParser, source: Resource, target: Resource): String {
        val objectMapper = ObjectMapper()
        val jsonDiff = JsonDiff.asJson(
            objectMapper.readValue(
                parser.encodeResourceToString(source),
                JsonNode::class.java
            ),
            objectMapper.readValue(
                parser.encodeResourceToString(target),
                JsonNode::class.java
            )
        )
        if (jsonDiff.size() == 0) {
            Log.i(
            "ResourceDao",
            "Target ${target.resourceType}/${target.id} is same as source."
            )
        }
        return jsonDiff.toString()
    }
}
