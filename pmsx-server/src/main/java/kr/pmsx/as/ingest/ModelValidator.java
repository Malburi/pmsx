package kr.pmsx.as.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** model.json 을 docs/schema/asis-model.schema.json (classpath:schema/ 복사본)으로 검증한다. */
@Component
public class ModelValidator {

    private final JsonSchema schema;

    public ModelValidator() {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        this.schema = factory.getSchema(SchemaLocation.of("classpath:schema/asis-model.schema.json"));
        this.schema.initializeValidators();
    }

    /** 위반 메시지 목록(최대 limit 건). 비어 있으면 통과. */
    public List<String> validate(JsonNode model, int limit) {
        Set<ValidationMessage> errors = schema.validate(model);
        return errors.stream().limit(limit).map(ValidationMessage::getMessage).toList();
    }
}
