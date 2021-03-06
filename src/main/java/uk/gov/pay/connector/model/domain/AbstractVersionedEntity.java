package uk.gov.pay.connector.model.domain;

import javax.persistence.Column;
import javax.persistence.MappedSuperclass;
import javax.persistence.Version;
import java.io.Serializable;

@MappedSuperclass
public abstract class AbstractVersionedEntity implements Serializable {
    @Version
    @Column(name = "version")
    private Long version;

    public AbstractVersionedEntity() {
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
