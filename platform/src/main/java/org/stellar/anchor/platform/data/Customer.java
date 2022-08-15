package org.stellar.anchor.platform.data;

import java.util.List;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.OneToMany;
import lombok.Data;

@Entity
@Data
public class Customer {
  @Id private String id;
  @OneToMany private List<CustomerStatus> statuses;
}
