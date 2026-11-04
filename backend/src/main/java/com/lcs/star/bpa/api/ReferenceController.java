package com.lcs.star.bpa.api;

import com.lcs.star.bpa.api.generated.ReferenceApi;
import com.lcs.star.bpa.api.generated.model.Activity;
import com.lcs.star.bpa.api.generated.model.Department;
import com.lcs.star.bpa.api.generated.model.Drawing;
import com.lcs.star.bpa.api.generated.model.Material;
import com.lcs.star.bpa.persistence.ReferenceRepository;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * The reference lists for the task screens (09 S20; built by BPA-6 per the PO's BPA-46 Q4 answer). Open to every logged-in
 * user (the contract's x-assumption); SecurityConfig's {@code anyRequest().authenticated()} gives the 401.
 */
@RestController
public class ReferenceController implements ReferenceApi {

    private final ReferenceRepository references;

    public ReferenceController(ReferenceRepository references) {
        this.references = references;
    }

    @Override
    public ResponseEntity<List<Activity>> listActivities() {
        return ResponseEntity.ok(references.activities());
    }

    @Override
    public ResponseEntity<List<Drawing>> listDrawings() {
        return ResponseEntity.ok(references.drawings());
    }

    @Override
    public ResponseEntity<List<Material>> listMaterials() {
        return ResponseEntity.ok(references.materials());
    }

    @Override
    public ResponseEntity<List<Department>> listDepartments() {
        return ResponseEntity.ok(references.departments());
    }
}
