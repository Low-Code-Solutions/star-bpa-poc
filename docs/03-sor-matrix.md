# 03 SOR matrix: STAR BPA POC

Status: TEMPLATE. Owner: Faraz. Complete in the slice-selection workshop.

Rule of thumb: the workflow owns the flow and its state; existing systems keep owning their data.

## Known SOR positions (from the FRD review)
| Data domain | SOR | Integration pattern | Note |
| --- | --- | --- | --- |
| Employee / person | myPeople | Lookup by ID; mocked in POC | Retained 1 Oct 2026 |
| Finance and procurement | D365 F&O (on-prem) | (confirm per entity) | |
| FMS-held data | FMS | Batch, file-based via Cloudera/Informatica | Retained |
| SAP-held data | SAP | (confirm) | Retained 1 Oct 2026 |
| Inventory, asset maintenance, quality, production planning | Saipem-hosted legacy system | Undocumented | Treat as read-only reference |

## Slice entities (fill in at the workshop)
| Entity | Key fields | SOR | POC access (read / write / reference / mock) | Real-time or batch | Reason if mastered in POC database |
| --- | --- | --- | --- | --- | --- |
| TBD | | | | | |

## POC-mastered data
| Entity | Why no existing system holds it | What happens at end of POC |
| --- | --- | --- |
| Process state (Camunda) | Owned by the workflow engine by design | Discarded |
| TBD | | |

## Mocks
| System | Mock type | Location in repo |
| --- | --- | --- |
| myPeople | API mock (WireMock) | mocks/ |
| TBD | | |
